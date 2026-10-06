package com.yodesla.omniverse.android.parental

import com.yodesla.omniverse.core.data.UserDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Parental PIN over the user-data key/value store. The PIN is never stored: only
 * "sha256hex:salt" (salt = 16 random bytes, hex). Locks are a JSON array of
 * "KIND|sourceId|categoryId" strings under a single setting key.
 */
class ParentalControls(
    private val userData: UserDataRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    companion object {
        const val PIN_KEY = "parental_pin"
        const val LOCKED_KEY = "parental_locked"
        /** "attempts:lockedUntilMs": persisted so an app restart can't reset the lockout. */
        const val ATTEMPTS_KEY = "parental_attempts"
        const val WRONG_ATTEMPTS = 5
        const val LOCKOUT_MS = 60_000L
        const val SESSION_MS = 600_000L
        private const val EXPIRY_TICK_MS = 1_000L

        private val SUGGEST_PATTERNS = listOf(
            Regex("\\badults?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bxxx\\b", RegexOption.IGNORE_CASE),
            Regex("18\\+"),
            Regex("\\+18\\b"),
            Regex("\\bporns?\\b", RegexOption.IGNORE_CASE),
            Regex("\\berotics?\\b", RegexOption.IGNORE_CASE),
            Regex("\\bfor adults\\b", RegexOption.IGNORE_CASE),
        )
    }

    private val _unlockedForSession = MutableStateFlow(false)
    val unlockedForSession: StateFlow<Boolean> = _unlockedForSession

    private val _lockedOutUntil = MutableStateFlow<Long?>(null)
    val lockedOutUntil: StateFlow<Long?> = _lockedOutUntil

    @Volatile
    private var lastTouchMs = 0L

    @Volatile
    private var wrongAttempts = 0

    @Volatile
    private var expiryJob: Job? = null

    val enabled: Flow<Boolean> = userData.setting(PIN_KEY).map { !it.isNullOrBlank() }

    fun lockedKeys(): Flow<Set<String>> = userData.setting(LOCKED_KEY).map { parseLocked(it) }

    fun isLocked(kind: String, sourceId: String, categoryId: String): Flow<Boolean> =
        lockedKeys().map { "$kind|$sourceId|$categoryId" in it }

    suspend fun setLocked(kind: String, sourceId: String, categoryId: String, locked: Boolean) {
        val key = "$kind|$sourceId|$categoryId"
        val current = parseLocked(userData.setting(LOCKED_KEY).first())
        val next = if (locked) current + key else current - key
        userData.putSetting(LOCKED_KEY, encodeLocked(next))
    }

    suspend fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        val salt = randomSalt()
        userData.putSetting(PIN_KEY, "${sha256Hex(pin + ":" + salt)}:$salt")
    }

    suspend fun clearPin(current: String): Boolean {
        if (!check(current)) return false
        wrongAttempts = 0
        _lockedOutUntil.value = null
        userData.putSetting(PIN_KEY, "")
        userData.putSetting(LOCKED_KEY, "")
        lockAgain()
        return true
    }

    suspend fun check(pin: String): Boolean {
        val now = clock()
        restoreAttempts()
        _lockedOutUntil.value?.let { until ->
            if (until > now) return false
            _lockedOutUntil.value = null
        }
        val stored = userData.setting(PIN_KEY).first()
        if (stored.isNullOrBlank()) return false
        val sep = stored.lastIndexOf(':')
        if (sep <= 0) return false
        val ok = constantTimeEquals(sha256Hex(pin + ":" + stored.substring(sep + 1)), stored.substring(0, sep))
        if (ok) {
            wrongAttempts = 0
            _lockedOutUntil.value = null
            saveAttempts()
            lastTouchMs = now
            _unlockedForSession.value = true
            scheduleExpiry()
        } else {
            wrongAttempts += 1
            if (wrongAttempts >= WRONG_ATTEMPTS) {
                wrongAttempts = 0
                _lockedOutUntil.value = now + LOCKOUT_MS
            }
            saveAttempts()
        }
        return ok
    }

    @Volatile private var attemptsRestored = false

    /** Loads the persisted counter/lockout once per process. */
    private suspend fun restoreAttempts() {
        if (attemptsRestored) return
        attemptsRestored = true
        val raw = userData.setting(ATTEMPTS_KEY).first() ?: return
        wrongAttempts = raw.substringBefore(':').toIntOrNull() ?: 0
        _lockedOutUntil.value = raw.substringAfter(':', "").toLongOrNull()?.takeIf { it > 0 }
    }

    private suspend fun saveAttempts() {
        userData.putSetting(ATTEMPTS_KEY, "$wrongAttempts:${_lockedOutUntil.value ?: 0}")
    }

    /**
     * What screens use: `visible(kind, sourceId, categoryId)`. While locked (not unlocked for this
     * session), locked categories and everything in them are hidden everywhere: category lists,
     * Home rows, Search. Kinds: LIVE, VOD, SERIES.
     */
    val visibility: Flow<(String, String, String) -> Boolean> =
        combine(lockedKeys(), unlockedForSession) { keys, unlocked ->
            { kind: String, src: String, cat: String -> unlocked || keys.isEmpty() || "$kind|$src|$cat" !in keys }
        }

    /** Runtime exclusions for SQL grouping. Empty during a PIN-unlocked session. */
    val excludedCategoryKeys: Flow<Set<String>> =
        combine(lockedKeys(), unlockedForSession) { keys, unlocked -> if (unlocked) emptySet() else keys }

    fun touch() {
        if (_unlockedForSession.value) lastTouchMs = clock()
    }

    fun lockAgain() {
        expiryJob?.cancel()
        expiryJob = null
        _unlockedForSession.value = false
    }

    fun suggestLock(categoryName: String): Boolean =
        SUGGEST_PATTERNS.any { it.containsMatchIn(categoryName) }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (clock() - lastTouchMs < SESSION_MS) {
                delay(EXPIRY_TICK_MS)
            }
            _unlockedForSession.value = false
        }
    }

    // kotlinx.serialization (pure Kotlin): works in JVM unit tests, unlike org.json's android.jar stubs.
    private fun encodeLocked(set: Set<String>): String =
        if (set.isEmpty()) "" else Json.encodeToString(ListSerializer(String.serializer()), set.sorted())

    private fun parseLocked(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { Json.decodeFromString(ListSerializer(String.serializer()), raw).toSet() }.getOrDefault(emptySet())
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i] - b[i])
        return diff == 0
    }
}
