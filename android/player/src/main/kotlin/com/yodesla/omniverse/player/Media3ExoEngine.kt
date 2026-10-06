package com.yodesla.omniverse.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Media3 implementation of [PlayerEngine] (PLAN.md §7). Main-thread only.
 *
 * - One ExoPlayer for the engine's lifetime; zapping reuses it (no surface teardown).
 * - Live tuning: fast start (1 s), up to [Tuning.maxBufferMs] buffered.
 * - Task 81: every player error and stall goes through [RetryPolicy] — transient drops reconnect
 *   with backoff, BehindLiveWindow rejoins the live edge, refusals/404/decoder errors give up.
 *   Pending retries die with stop()/release(), so a backgrounded or closed player never retries.
 * - Connection discipline: stop() before every new source, so the old HTTP request is closed.
 */
@OptIn(UnstableApi::class)
class Media3ExoEngine(
    context: Context,
    userAgent: String,
    private val tuning: Tuning = Tuning(),
    private val retryPolicy: RetryPolicy = RetryPolicy { SystemClock.elapsedRealtime() },
) : PlayerEngine {

    data class Tuning(
        val minBufferMs: Int = 15_000,
        val maxBufferMs: Int = 50_000,
        val bufferForPlaybackMs: Int = 1_000,
        val bufferForPlaybackAfterRebufferMs: Int = 2_500,
        /** No progress for this long while we want to play → reconnect. */
        val stallTimeoutMs: Long = 12_000,
        /**
         * Hard byte cap on the buffer. Time-only buffering at remux bitrates (60-80 Mbps) needs
         * hundreds of MB, the heap can't hold it, loading stalls under memory pressure and the
         * provider drops the idle connection. 0 = derive from the heap size.
         */
        val targetBufferBytes: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val _tracks = MutableStateFlow(TrackInfo())
    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    private val audioFeatures = LocalAudioFeatureBuffer()
    private val pcmFeatureProcessor = LocalPcmFeatureProcessor(audioFeatures)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val tracks: StateFlow<TrackInfo> = _tracks.asStateFlow()
    override val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()
    val skipAnalysisRevision: StateFlow<Long> = audioFeatures.revision

    fun setSkipAnalysisEnabled(enabled: Boolean) {
        audioFeatures.setEnabled(enabled)
        if (!enabled) pcmFeatureProcessor.clearScratch()
    }

    fun skipAnalysisSnapshot(): List<AudioFeatureFrame> = audioFeatures.snapshot()

    fun skipAnalysisLatestPositionMs(): Long? = audioFeatures.latestPositionMs()

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(15_000)

    private val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )

    /** Exposed only for the surface composable; feature code talks to [PlayerEngine]. */
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        pcmFeatureProcessor,
                    ),
                )
                .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true),
    )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    tuning.minBufferMs,
                    tuning.maxBufferMs,
                    tuning.bufferForPlaybackMs,
                    tuning.bufferForPlaybackAfterRebufferMs,
                )
                .setTargetBufferBytes(bufferBytes(context, tuning.targetBufferBytes))
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var current: PlaybackSpec? = null
    /** The spec this engine currently holds (buffered or playing); null after stop()/release(). */
    val currentSpec: PlaybackSpec? get() = current
    private var attempt = 1
    private var wantPlaying = false
    private var lastProgressAt = 0L
    private var lastPosition = -1L
    private var watchdog: Job? = null
    // Task 111 L1: a primed (not yet adopted) spare must not hold a second connection indefinitely.
    private var primeWatchdog: Job? = null
    private var reconnectJob: Job? = null
    private var frameRateDrivenBySurface = false

    /**
     * Task 100: with "Always" the player drives the display itself through [VideoFrameRateApplier]
     * (`CHANGE_FRAME_RATE_ALWAYS`), so Media3's own seamless-only switching is turned off to keep the
     * two from fighting over the surface. Otherwise Media3 keeps its default: seamless-only for VOD,
     * never on live.
     */
    fun setFrameRateMatch(mode: FrameRateMatchMode) {
        frameRateDrivenBySurface = mode == FrameRateMatchMode.ALWAYS
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = publish()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Task 81: real playback is what resets the retry counters (after 60 s in the policy).
                if (isPlaying) {
                    retryPolicy.onHealthyPlayback()
                    attempt = 1
                }
                publish()
            }
            override fun onPlayerError(error: PlaybackException) = handleError(error)
            override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)
            override fun onVideoSizeChanged(videoSize: VideoSize) = publishTracks(player.currentTracks)
        })
    }

    override fun play(spec: PlaybackSpec, startPositionMs: Long) = open(spec, startPositionMs, resetAttempts = true)

    override fun zap(spec: PlaybackSpec) = open(spec, 0, resetAttempts = true)

    private fun open(spec: PlaybackSpec, startPositionMs: Long, resetAttempts: Boolean) {
        // Rule 7: close the previous upstream request before opening a new one.
        player.stop()
        reconnectJob?.cancel()
        if (resetAttempts) {
            attempt = 1
            retryPolicy.reset()
        }
        current = spec
        _diagnostics.value = PlaybackDiagnostics(deliveryMode = spec.deliveryMode)
        wantPlaying = true
        http.setDefaultRequestProperties(spec.headers)
        player.videoChangeFrameRateStrategy =
            if (spec.isLive || frameRateDrivenBySurface) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        val item = MediaItem.Builder()
            .setUri(spec.url)
            .setMimeType(spec.mimeHint.toMime())
            .build()
        if (startPositionMs > 0 && !spec.isLive) player.setMediaItem(item, startPositionMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        _state.value = PlayerState.Loading(spec, attempt)
        startWatchdog()
    }

    /**
     * Task 94: buffer [spec] on a hidden engine WITHOUT playing it — no surface, playWhenReady=false
     * (so no audio and no audio-focus steal from the engine that is actually on screen) and no
     * watchdog. [adopt] turns the primed stream into the playing one on a zap. Closes any previous
     * upstream request first (rule 7), so a primed engine holds exactly one connection.
     */
    fun prime(spec: PlaybackSpec) {
        player.stop()
        primeWatchdog?.cancel()
        primeWatchdog = null
        reconnectJob?.cancel()
        attempt = 1
        retryPolicy.reset()
        current = spec
        _diagnostics.value = PlaybackDiagnostics(deliveryMode = spec.deliveryMode)
        wantPlaying = false
        http.setDefaultRequestProperties(spec.headers)
        player.videoChangeFrameRateStrategy =
            if (spec.isLive) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        val item = MediaItem.Builder()
            .setUri(spec.url)
            .setMimeType(spec.mimeHint.toMime())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = false
        _state.value = PlayerState.Loading(spec, 1)
        // Task 111 L1: cap the prime. If it is not adopted within PRIME_MAX_MS the spare releases
        // its connection; the next zap re-arms it. This bounds the concurrent-stream window.
        primeWatchdog?.cancel()
        primeWatchdog = scope.launch {
            delay(PRIME_MAX_MS)
            if (shouldReleasePrime(wantPlaying, player.isPlaying, current === spec)) {
                player.stop()
                current = null
                _state.value = PlayerState.Idle
            }
        }
    }

    /** Task 94: the primed stream becomes the playing one (surface is re-attached by the UI). */
    fun adopt() {
        if (current == null) return
        primeWatchdog?.cancel()
        primeWatchdog = null
        wantPlaying = true
        lastProgressAt = SystemClock.elapsedRealtime()
        player.play()
        startWatchdog()
    }

    override fun pause() {
        wantPlaying = false
        player.pause()
    }

    override fun resume() {
        wantPlaying = true
        lastProgressAt = SystemClock.elapsedRealtime()
        player.play()
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    override fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    override fun stop() {
        wantPlaying = false
        watchdog?.cancel()
        primeWatchdog?.cancel()
        primeWatchdog = null
        // Task 81: a backgrounded or closed player never retries — kill any pending reconnect.
        reconnectJob?.cancel()
        player.stop()
        current = null
        _diagnostics.value = PlaybackDiagnostics()
        _state.value = PlayerState.Idle
    }

    override fun release() {
        stop()
        scope.cancel()
        player.release()
    }

    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET }

    override fun selectAudio(trackId: String?) = select(C.TRACK_TYPE_AUDIO, trackId)

    override fun selectSubtitle(trackId: String?) = select(C.TRACK_TYPE_TEXT, trackId)

    /**
     * Viewer defaults applied before tracks are chosen: [audioLanguage] (ISO 639 code, null = the
     * stream's default) and [subtitles] = "off" | "forced" (only forced/foreign-part tracks, the
     * normal default) | "always" (prefer a subtitle track in [audioLanguage], else English).
     */
    fun setLanguagePreferences(audioLanguage: String?, subtitles: String?) {
        preferAudioLanguage(audioLanguage)
        applySubtitleDefault(subtitles, audioLanguage)
    }

    /** Prefer [audioLanguage] for audio (null = the stream's default). Leaves subtitle selection alone. */
    fun preferAudioLanguage(audioLanguage: String?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLanguage).build()
    }

    /**
     * The text-track half of [setLanguagePreferences] on its own, so a per-show audio choice can be
     * applied without disturbing the subtitle default (and vice versa).
     */
    fun applySubtitleDefault(subtitles: String?, audioLanguage: String?) {
        val b = player.trackSelectionParameters.buildUpon()
        when (subtitles) {
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "always" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(audioLanguage ?: "en").setSelectUndeterminedTextLanguage(true)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(null)
        }
        player.trackSelectionParameters = b.build()
    }

    /** The language of the audio track now playing, or null when none is selected or it carries none. */
    fun selectedAudioLanguage(): String? = selectedTrackRole(C.TRACK_TYPE_AUDIO)?.language

    /** The subtitle track now showing (language + forced-only role), or null when subtitles are off. */
    fun selectedSubtitle(): SelectedTrackRole? = selectedTrackRole(C.TRACK_TYPE_TEXT)

    private fun selectedTrackRole(type: Int): SelectedTrackRole? {
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i) || !group.isTrackSelected(i)) continue
                val f = group.getTrackFormat(i)
                return SelectedTrackRole(f.language, (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
            }
        }
        return null
    }

    private fun select(type: Int, trackId: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val (g, t) = trackId.split(':').map { it.toInt() }
            val group = player.currentTracks.groups.getOrNull(g) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun publish() {
        val spec = current ?: return
        if (_state.value is PlayerState.Failed) return
        _state.value = when (player.playbackState) {
            Player.STATE_BUFFERING ->
                if (player.currentPosition <= 0 && !player.isPlaying) PlayerState.Loading(spec, attempt) else PlayerState.Buffering(spec)
            Player.STATE_READY -> if (player.isPlaying) PlayerState.Playing(spec) else PlayerState.Paused(spec)
            Player.STATE_ENDED -> PlayerState.Ended(spec)
            else -> _state.value
        }
    }

    private fun publishTracks(tracks: Tracks) {
        val audio = mutableListOf<Track>()
        val subs = mutableListOf<Track>()
        tracks.groups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val track = Track(
                    id = "$gi:$ti",
                    label = f.label ?: f.language ?: "Track ${ti + 1}",
                    language = f.language,
                    selected = group.isTrackSelected(ti),
                    forcedOnly = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += track
                    C.TRACK_TYPE_TEXT -> subs += track
                }
            }
        }
        val vs = player.videoSize
        _tracks.value = TrackInfo(audio, subs, vs.width, vs.height, player.videoFormat?.frameRate ?: 0f)
        publishDiagnostics()
    }

    fun refreshDiagnostics() = publishDiagnostics()

    private fun publishDiagnostics() {
        val video = player.videoFormat
        val audio = player.audioFormat
        val size = player.videoSize
        _diagnostics.value = PlaybackDiagnostics(
            width = size.width, height = size.height,
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
            pixelAspectRatio = size.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f,
            videoCodec = video?.sampleMimeType,
            audioCodec = audio?.sampleMimeType,
            encodedBitrate = video?.bitrate?.takeIf { it > 0 } ?: 0,
            bufferedMs = player.totalBufferedDuration.coerceAtLeast(0),
            deliveryMode = current?.deliveryMode ?: com.yodesla.omniverse.core.model.DeliveryMode.UNKNOWN,
        )
    }

    private fun handleError(error: PlaybackException) {
        val spec = current ?: return
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val failure = when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> StreamFailure.BehindLiveWindow
            http != null -> StreamFailure.HttpStatus(http.responseCode)
            error.errorCode in DECODER_CODES -> StreamFailure.Decoder
            error.errorCode in NETWORK_CODES -> StreamFailure.Network
            else -> StreamFailure.Other
        }
        when (val decision = retryPolicy.onStreamFailure(failure)) {
            is RetryDecision.RetryAfter -> scheduleReconnect(spec, decision)
            is RetryDecision.GiveUp -> giveUp(spec, decision.reason, "${error.errorCodeName} (${spec.redacted})")
        }
    }

    private fun scheduleReconnect(spec: PlaybackSpec, decision: RetryDecision.RetryAfter) {
        if (decision.seekToLive && decision.delayMs == 0L) {
            // BehindLiveWindow: rejoin the live edge on the spot — no counter, no state change.
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        attempt = decision.attempt + 1
        _state.value = PlayerState.Loading(spec, attempt)
        reconnectJob?.cancel()
        // Keep the watchdog alive as a backstop; the pending job blocks it from double-scheduling.
        lastProgressAt = SystemClock.elapsedRealtime()
        reconnectJob = scope.launch {
            delay(decision.delayMs)
            // Cancelled by stop()/release() → a backgrounded or closed player never retries.
            if (current === spec && wantPlaying) {
                // VOD resumes at the last known position; live rejoins the live edge.
                open(spec, if (spec.isLive) 0L else player.currentPosition, resetAttempts = false)
            }
        }
    }

    private fun giveUp(spec: PlaybackSpec, reason: GiveUpReason, detail: String) {
        watchdog?.cancel()
        reconnectJob?.cancel()
        attempt = 1
        _state.value = PlayerState.Failed(spec, reason.toFailureKind(), detail)
    }

    private fun GiveUpReason.toFailureKind(): FailureKind = when (this) {
        GiveUpReason.PROVIDER_REFUSED -> FailureKind.DENIED
        GiveUpReason.NOT_FOUND -> FailureKind.NOT_FOUND
        GiveUpReason.DECODER -> FailureKind.UNSUPPORTED
        GiveUpReason.EXHAUSTED -> FailureKind.NETWORK
        GiveUpReason.OTHER -> FailureKind.OTHER
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        lastProgressAt = SystemClock.elapsedRealtime()
        lastPosition = -1
        watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                val spec = current ?: continue
                val pos = player.currentPosition
                val now = SystemClock.elapsedRealtime()
                if (!wantPlaying || player.isPlaying && pos != lastPosition) {
                    lastProgressAt = now
                    lastPosition = pos
                    continue
                }
                if (now - lastProgressAt > tuning.stallTimeoutMs) {
                    if (reconnectJob?.isActive == true) {
                        // A policy retry is already pending — don't stack a second one on top.
                        lastProgressAt = now
                    } else {
                        when (val decision = retryPolicy.onStreamFailure(StreamFailure.Network)) {
                            is RetryDecision.RetryAfter -> scheduleReconnect(spec, decision)
                            is RetryDecision.GiveUp -> {
                                giveUp(spec, decision.reason, "Stalled (${spec.redacted})")
                                player.stop()
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun MimeHint.toMime(): String? = when (this) {
        MimeHint.MPEG_TS -> MimeTypes.VIDEO_MP2T
        MimeHint.HLS -> MimeTypes.APPLICATION_M3U8
        MimeHint.MP4 -> MimeTypes.VIDEO_MP4
        MimeHint.MKV -> MimeTypes.VIDEO_MATROSKA
        MimeHint.UNKNOWN -> null
    }

    private companion object {
        // Task 111 L1: a primed spare releases its connection if it is not adopted within this window.
        const val PRIME_MAX_MS = 60_000L
        val NETWORK_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
        val DECODER_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
    }
}

/** A quarter of the app heap, 32-128 MB (Shield/Onn: 192 MB heap → 48 MB; largeHeap → 128 MB). */
private fun bufferBytes(context: Context, override: Int): Int {
    if (override > 0) return override
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val heapMb = am.largeMemoryClass.coerceAtLeast(am.memoryClass)
    return (heapMb / 4).coerceIn(32, 128) * 1024 * 1024
}

/**
 * Task 111 L1: whether the prime watchdog must release a primed spare. It releases only when the
 * prime is still un-adopted and not playing (the current channel has not zapped onto it); an adopted
 * or playing prime is kept.
 */
internal fun shouldReleasePrime(wantPlaying: Boolean, isPlaying: Boolean, primed: Boolean): Boolean =
    primed && !wantPlaying && !isPlaying
