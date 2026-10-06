package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.model.Redact

/**
 * Task 104: the in-app log ring buffer. [CAPACITY] lines, oldest dropped first, and every line is
 * run through [Redact] the moment it is added — a credential can enter the buffer but never leave
 * it into a crash report or a Downloads file.
 *
 * The point is context, not volume: when [CrashLog] writes a report it copies this whole buffer
 * under the trace, so "playback crashed" arrives next to the sync stages and startup milestones that
 * led to it. Nothing here is ever uploaded, and the buffer lives only for the life of the process.
 */
object AppLog {
    /** How many lines a crash report carries. */
    const val CAPACITY = 50

    /** One line is a milestone, not a stack trace; anything longer is cut. */
    const val MAX_LINE_CHARS = 400

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val whitespace = Regex("\\s+")

    /** Append one event. Whitespace runs are collapsed so an event is exactly one ring slot. */
    fun log(event: String) {
        val line = Redact.text(whitespace.replace(event, " ")).trim().take(MAX_LINE_CHARS)
        if (line.isEmpty()) return
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    /** Oldest first, at most [CAPACITY] scrubbed lines. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}
