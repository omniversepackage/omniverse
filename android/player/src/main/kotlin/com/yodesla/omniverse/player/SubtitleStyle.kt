package com.yodesla.omniverse.player

/**
 * Task 84j: subtitle appearance + VOD-only timing.
 *
 * The four appearance dimensions are stored per profile as four plain settings strings (see the
 * keys below); "default" / absent means "follow the system caption style and the cues' own styling"
 * for that dimension. [resolveSubtitleStyle] turns the raw strings into the concrete values the
 * player feeds Media3, and is a pure function so it can be unit-tested without Android or a TV.
 *
 * The persisted tokens live here (single source of truth) and are shared by Settings, the players
 * and the style dialog. Labels are plain English, matching the rest of the player UI (TrackPanel).
 */

// Per-profile setting keys (see UserDataRepositoryImpl.isProfileSetting).
const val SUBTITLE_STYLE_SIZE = "subtitle_style_size"
const val SUBTITLE_STYLE_BACKGROUND = "subtitle_style_background"
const val SUBTITLE_STYLE_COLOR = "subtitle_style_color"
const val SUBTITLE_STYLE_POSITION = "subtitle_style_position"

/** The token that means "no override for this dimension". */
const val SUBTITLE_STYLE_DEFAULT = "default"

// CaptionStyleCompat.EDGE_TYPE_OUTLINE / opaque black, inlined so the helper stays Android-free.
private const val EDGE_TYPE_OUTLINE = 1
private const val COLOR_BLACK = 0xFF000000.toInt()

enum class SubtitleSize(val token: String, val label: String, val fraction: Float) {
    SMALL("small", "Small", 0.040f),
    MEDIUM("medium", "Medium", 0.0533f),
    LARGE("large", "Large", 0.070f),
    EXTRA_LARGE("xlarge", "Extra large", 0.090f),
}

enum class SubtitleBackground(val token: String, val label: String, val argb: Int) {
    NONE("none", "None", 0x00000000),
    SEMI("semi", "Semi", 0x99000000.toInt()),
    SOLID("solid", "Solid box", COLOR_BLACK),
}

enum class SubtitleTextColor(val token: String, val label: String, val argb: Int) {
    WHITE("white", "White", 0xFFFFFFFF.toInt()),
    YELLOW("yellow", "Yellow", 0xFFFFFF00.toInt()),
}

enum class SubtitlePosition(val token: String, val label: String, val padding: Float) {
    BOTTOM("bottom", "Bottom", 0.08f),
    RAISED("raised", "Raised", 0.20f),
}

/** One choice in the style dialog: [value] is the persisted token ("default" = follow the system). */
data class StyleOption(val value: String, val label: String)

private fun optionsFor(values: List<String>, labels: List<String>): List<StyleOption> =
    listOf(StyleOption(SUBTITLE_STYLE_DEFAULT, "Default")) + values.zip(labels).map { StyleOption(it.first, it.second) }

val SUBTITLE_SIZE_OPTIONS: List<StyleOption> =
    optionsFor(SubtitleSize.entries.map { it.token }, SubtitleSize.entries.map { it.label })
val SUBTITLE_BACKGROUND_OPTIONS: List<StyleOption> =
    optionsFor(SubtitleBackground.entries.map { it.token }, SubtitleBackground.entries.map { it.label })
val SUBTITLE_COLOR_OPTIONS: List<StyleOption> =
    optionsFor(SubtitleTextColor.entries.map { it.token }, SubtitleTextColor.entries.map { it.label })
val SUBTITLE_POSITION_OPTIONS: List<StyleOption> =
    optionsFor(SubtitlePosition.entries.map { it.token }, SubtitlePosition.entries.map { it.label })

/**
 * Everything EngineSubtitles needs to draw subtitles the way the profile chose, as plain values
 * (no Android types) so the mapping is testable. Null [SubtitleStyleSpec] means "use the system
 * caption style and keep the cues' embedded styling".
 */
data class SubtitleStyleSpec(
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val windowColorArgb: Int,
    val edgeType: Int,
    val edgeColorArgb: Int,
    val fractionalTextSize: Float,
    val bottomPaddingFraction: Float,
    val applyEmbeddedStyles: Boolean,
)

/** The four persisted strings for a profile, before mapping to concrete values. */
data class SubtitleStyleRaw(val size: String?, val background: String?, val color: String?, val position: String?)

fun SubtitleStyleRaw.toSpec(): SubtitleStyleSpec? = resolveSubtitleStyle(size, background, color, position)
fun SubtitleStyleRaw.summary(): String = subtitleStyleSummary(size, background, color, position)

private fun <T> parseToken(raw: String?, values: List<T>, token: (T) -> String): T? {
    val key = raw?.trim()?.lowercase() ?: return null
    if (key.isEmpty() || key == SUBTITLE_STYLE_DEFAULT) return null
    return values.firstOrNull { token(it) == key }
}

/**
 * Map the four persisted strings to concrete Media3 values. Returns null only when every dimension
 * is default (then the player keeps the system style + embedded cue styling). Any explicit choice
 * switches embedded styling off and fills unchosen dimensions with sensible baselines
 * (white text, semi-transparent box, medium size, bottom).
 */
fun resolveSubtitleStyle(
    size: String?,
    background: String?,
    color: String?,
    position: String?,
): SubtitleStyleSpec? {
    val s = parseToken(size, SubtitleSize.entries) { it.token }
    val b = parseToken(background, SubtitleBackground.entries) { it.token }
    val col = parseToken(color, SubtitleTextColor.entries) { it.token }
    val pos = parseToken(position, SubtitlePosition.entries) { it.token }
    if (s == null && b == null && col == null && pos == null) return null
    val bg = (b ?: SubtitleBackground.SEMI).argb
    return SubtitleStyleSpec(
        textColorArgb = (col ?: SubtitleTextColor.WHITE).argb,
        backgroundColorArgb = bg,
        windowColorArgb = bg,
        edgeType = EDGE_TYPE_OUTLINE,
        edgeColorArgb = COLOR_BLACK,
        fractionalTextSize = (s ?: SubtitleSize.MEDIUM).fraction,
        bottomPaddingFraction = (pos ?: SubtitlePosition.BOTTOM).padding,
        applyEmbeddedStyles = false,
    )
}

/** Human summary for the Settings row: "Default", or the non-default choices joined by " · ". */
fun subtitleStyleSummary(
    size: String?,
    background: String?,
    color: String?,
    position: String?,
): String {
    val parts = buildList {
        parseToken(size, SubtitleSize.entries) { it.token }?.let { add(it.label) }
        parseToken(background, SubtitleBackground.entries) { it.token }?.let { add(it.label) }
        parseToken(color, SubtitleTextColor.entries) { it.token }?.let { add(it.label) }
        parseToken(position, SubtitlePosition.entries) { it.token }?.let { add(it.label) }
    }
    return if (parts.isEmpty()) "Default" else parts.joinToString("  ·  ")
}

// --- VOD-only subtitle timing (per playback session, never persisted) -------------------------

const val SUBTITLE_DELAY_STEP_MS = 100L
const val SUBTITLE_DELAY_MIN_MS = -10_000L
const val SUBTITLE_DELAY_MAX_MS = 10_000L

/**
 * Move the subtitle delay by one step in [direction] (-1 earlier, +1 later, anything else = no
 * change) and clamp to ±10 s. Pure so the range/step behaviour is unit-testable.
 */
fun stepSubtitleDelay(currentMs: Long, direction: Int): Long {
    val delta = direction.coerceIn(-1, 1) * SUBTITLE_DELAY_STEP_MS
    return (currentMs + delta).coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
}

/** "-3.2 s", "+0.1 s", "0 s" — the label shown next to the delay control. */
fun formatSubtitleDelay(ms: Long): String = when {
    ms == 0L -> "0 s"
    ms > 0 -> "+${ms / 1000.0} s"
    else -> "${ms / 1000.0} s"
}
