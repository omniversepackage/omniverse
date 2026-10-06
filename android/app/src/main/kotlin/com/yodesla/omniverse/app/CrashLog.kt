package com.yodesla.omniverse.app

import android.content.Context
import android.os.Build
import com.yodesla.omniverse.core.model.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** One crash report on disk: filesDir/crash/crash-<epochMillis>.txt (task 78). */
data class CrashInfo(val name: String, val epochMs: Long, val summary: String)

/**
 * On-device crash log (task 78): the app goes out as a public APK and there is no upload —
 * a friend who hits a crash can open Settings › Diagnostics and read or show us the file.
 *
 * NO network, NO third-party SDK. The handler writes the report, then chains to the handler
 * that was installed before it, so Android's normal crash behaviour (dialog / process kill)
 * is untouched. Every entry point is wrapped: a crash logger that itself crashes would turn
 * one bug into two.
 */
object CrashLog {
    const val DIR_NAME = "crash"
    const val FILE_PREFIX = "crash-"
    const val FILE_SUFFIX = ".txt"
    const val FLAG_FILE = "crash-pending.flag"
    const val MAX_FILES = 5
    const val TRACE_MARK = "--- Trace ---"
    const val LOG_MARK = "--- Log ring (last ${AppLog.CAPACITY}) ---"

    /** Task 104: per-profile viewer setting — the newest crash epoch this profile has already seen. */
    const val NOTICE_SETTING_KEY = "crash_notice"

    fun dir(filesDir: File): File = File(filesDir, DIR_NAME)

    fun dirOf(context: Context): File = dir(context.filesDir)

    fun flagFile(crashDir: File): File = File(crashDir, FLAG_FILE)

    /**
     * Install as early as possible (OmniverseApp.onCreate). The previous handler is captured
     * before we replace it and always runs afterwards; if there was none, the process halts
     * with a nonzero status like the default handler does.
     */
    fun install(context: Context) {
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val crashDir = dir(context.filesDir)
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { AppLog.log("uncaught exception on ${thread.name}: ${error}") }
                runCatching {
                    writeReport(
                        error, crashDir,
                        appInfo = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidInfo = Build.VERSION.RELEASE,
                        threadName = thread.name,
                        logLines = AppLog.snapshot(),
                    )
                }
                runCatching {
                    if (previous != null) previous.uncaughtException(thread, error)
                    else Runtime.getRuntime().halt(1)
                }
            }
        }
    }

    /** Write one report and rotate the folder to the newest [MAX_FILES]. Never throws.
     *  [logLines] is the [AppLog] ring buffer (oldest first); it is scrubbed again here so a caller
     *  that hands over raw lines cannot smuggle a credential past [Redact]. */
    fun writeReport(
        error: Throwable,
        crashDir: File,
        appInfo: String,
        deviceInfo: String,
        androidInfo: String,
        threadName: String,
        logLines: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): File? = runCatching {
        if (!crashDir.isDirectory && !crashDir.mkdirs()) return@runCatching null
        val report = buildString {
            append("Omniverse crash report\n")
            append("time: ").append(nowMs).append('\n')
            append("app: ").append(appInfo).append('\n')
            append("android: ").append(androidInfo).append('\n')
            append("device: ").append(deviceInfo).append('\n')
            append("thread: ").append(threadName).append('\n')
            append(TRACE_MARK).append('\n')
            append(trace(error))
            append('\n').append(LOG_MARK).append('\n')
            append(logRing(logLines))
        }
        val file = File(crashDir, "$FILE_PREFIX$nowMs$FILE_SUFFIX")
        file.writeText(report)
        flagFile(crashDir).createNewFile()
        rotate(crashDir)
        file
    }.getOrNull()

    /** Full trace incl. every cause, each line run through Redact: Xtream/Plex credentials in
     *  URLs must never reach the report a friend shows us. */
    private fun trace(error: Throwable): String {
        val raw = StringWriter().also { sw -> error.printStackTrace(PrintWriter(sw)) }.toString()
        return raw.lines().joinToString("\n") { Redact.text(it) }
    }

    /** The last [AppLog.CAPACITY] in-app log lines, oldest first, each one scrubbed. */
    private fun logRing(lines: List<String>): String =
        lines.map { Redact.text(it) }.takeLast(AppLog.CAPACITY).joinToString("\n")

    /** Newest [MAX_FILES] crash files survive; the rest are deleted. Never throws. */
    private fun rotate(crashDir: File) {
        runCatching {
            crashDir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_FILES)
                ?.forEach { f -> runCatching { f.delete() } }
        }
    }

    /** Epoch millis from a crash file name, or null when it isn't one. */
    fun crashEpochMs(name: String): Long? =
        Regex("^$FILE_PREFIX(\\d+)$FILE_SUFFIX$").find(name)?.groupValues?.get(1)?.toLongOrNull()

    /** First line of the exception in a report, for the Diagnostics list. */
    fun summary(text: String): String =
        text.substringAfter("$TRACE_MARK\n").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    suspend fun list(crashDir: File): List<CrashInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                crashDir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                    ?.mapNotNull { f -> crashEpochMs(f.name)?.let { ms -> CrashInfo(f.name, ms, summary(f.readText())) } }
                    ?.sortedByDescending { it.epochMs }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

    suspend fun read(crashDir: File, name: String): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { File(crashDir, name).readText() }.getOrNull()
    }

    /** Delete every crash report (and the pending flag). Returns how many files were removed. */
    suspend fun deleteAll(crashDir: File): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val files = crashDir.listFiles { f ->
                f.isFile && ((f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)) || f.name == FLAG_FILE)
            } ?: return@runCatching 0
            var n = 0
            files.forEach { f -> if (f.delete()) n++ }
            n
        }.getOrDefault(0)
    }

    /** True when a crash happened since the last time the app showed the notice. */
    fun flagExists(crashDir: File): Boolean = runCatching { flagFile(crashDir).exists() }.getOrDefault(false)

    fun deleteFlag(crashDir: File) {
        runCatching { flagFile(crashDir).delete() }
    }

    /** Newest crash epoch on disk, or null when no report is left. Never throws. */
    fun newestEpochMs(crashDir: File): Long? = runCatching {
        crashDir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.mapNotNull { crashEpochMs(it.name) }?.maxOrNull()
    }.getOrNull()

    /**
     * Task 104: the crash notice is per profile. A crash is news to a profile only when the newest
     * report on disk is newer than the newest crash epoch that profile already saw.
     */
    fun noticeDue(newestMs: Long?, seenMs: Long?): Boolean = newestMs != null && (seenMs == null || newestMs > seenMs)
}
