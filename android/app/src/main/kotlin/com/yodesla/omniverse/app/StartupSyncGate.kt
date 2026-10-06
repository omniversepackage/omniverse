package com.yodesla.omniverse.app

/**
 * Task 101: Home rows first, startup sync after.
 *
 * The rule, kept as pure logic with an injectable clock so it is unit-testable:
 *  - nothing syncs before the first frame is drawn (the gate is closed until then);
 *  - once Home's cached rows are on screen the sync may start immediately;
 *  - if Home never shows rows (empty install, deep link straight into a title), the sync starts
 *    anyway [maxDelayMs] after the first frame, so a Home with nothing cached cannot starve the
 *    catalog refresh forever.
 *
 * So the startup sync begins at `min(Home rows visible, first frame + maxDelayMs)`.
 */
internal class StartupSyncGate(
    private val nowMs: () -> Long,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    @Volatile private var firstFrameAtMs: Long? = null

    /** When Home's cached rows landed (null = not yet). */
    @Volatile var homeRowsVisibleAtMs: Long? = null
        private set

    /** Called once the first Compose frame is drawn. */
    fun firstFrameDrawn() {
        if (firstFrameAtMs == null) firstFrameAtMs = nowMs()
    }

    /** Called once Home has rows on screen. Idempotent: the first call wins. */
    fun homeRowsVisible() {
        if (homeRowsVisibleAtMs == null) homeRowsVisibleAtMs = nowMs()
    }

    /** Milliseconds the startup sync still has to wait; 0 means it may run now. */
    fun syncWaitMs(): Long {
        val frame = firstFrameAtMs ?: return maxDelayMs
        if (homeRowsVisibleAtMs != null) return 0L
        return maxOf(0L, frame + maxDelayMs - nowMs())
    }

    companion object {
        /** Task 101: "…or ~2 s after the first frame". */
        const val DEFAULT_MAX_DELAY_MS = 2_000L
    }
}
