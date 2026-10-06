package com.yodesla.omniverse.app

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Pure-JVM tests for the on-device crash log (task 78): rotation, redaction, report format. */
class CrashLogTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File {
        val f = File.createTempFile("crashdir", ".tmp")
        assertTrue(f.delete())
        assertTrue(f.mkdirs())
        dirs.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        dirs.forEach { dir -> dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    private fun write(dir: File, atMs: Long): File? = CrashLog.writeReport(
        RuntimeException("boom at $atMs"), dir,
        appInfo = "9.9.9 (build 999)", deviceInfo = "Test TV", androidInfo = "14", threadName = "main",
        nowMs = atMs,
    )

    @Test fun rotationKeepsOnlyTheNewestFive() {
        val dir = newDir()
        for (i in 1..13) assertNotNull(write(dir, 1_000L + i))
        val names = dir.listFiles { f -> f.name.startsWith(CrashLog.FILE_PREFIX) && f.name.endsWith(CrashLog.FILE_SUFFIX) }!!.map { it.name }
        assertEquals(CrashLog.MAX_FILES, names.size)
        val kept = names.mapNotNull { CrashLog.crashEpochMs(it) }.sorted()
        assertEquals((1_009L..1_013L).toList(), kept)
        // The eight oldest are gone.
        for (i in 1..8) assertFalse(File(dir, CrashLog.FILE_PREFIX + (1_000L + i) + CrashLog.FILE_SUFFIX).exists())
        assertEquals(1_013L, CrashLog.newestEpochMs(dir))
    }

    @Test fun logRingIsWrittenUnderItsOwnMarkAndScrubbed() {
        val dir = newDir()
        val file = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("playback crashed"), dir,
                appInfo = "1.0 (build 1)", deviceInfo = "M", androidInfo = "14", threadName = "main",
                logLines = listOf(
                    "+12 ms  startup Application.onCreate",
                    "+1 200 ms  startup Home rows visible: 6 rows / 40 cards",
                    "sync s1 failed: GET http://alice:s3cret@tv.example.com/live failed",
                ),
                nowMs = 9_000L,
            ),
        )
        val text = file.readText()
        val lines = text.lines()
        assertTrue(CrashLog.LOG_MARK in lines)
        val ring = lines.subList(lines.indexOf(CrashLog.LOG_MARK) + 1, lines.size)
        assertEquals("+12 ms  startup Application.onCreate", ring.first())
        assertContains(text, "http://***:***@tv.example.com/live")
        assertFalse("s3cret" in text)
        assertFalse("alice" in text)
        // The trace still comes first, so summary() keeps reading the exception line.
        assertTrue(lines.indexOf(CrashLog.TRACE_MARK) < lines.indexOf(CrashLog.LOG_MARK))
        assertEquals("java.lang.RuntimeException: playback crashed", CrashLog.summary(text))
    }

    @Test fun logRingIsCappedAtFiftyLines() {
        val dir = newDir()
        val file = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("boom"), dir,
                appInfo = "1.0 (build 1)", deviceInfo = "M", androidInfo = "14", threadName = "main",
                logLines = (1..80).map { "event $it" }, nowMs = 9_500L,
            ),
        )
        val lines = file.readText().lines()
        val ring = lines.subList(lines.indexOf(CrashLog.LOG_MARK) + 1, lines.size)
        assertEquals(AppLog.CAPACITY, ring.size)
        assertEquals("event 31", ring.first())
        assertEquals("event 80", ring.last())
    }

    @Test fun crashNoticeIsDueOnlyForNewerCrashes() {
        assertFalse(CrashLog.noticeDue(null, null))
        assertTrue(CrashLog.noticeDue(10L, null))
        assertFalse(CrashLog.noticeDue(10L, 10L))
        assertTrue(CrashLog.noticeDue(20L, 10L))
        assertFalse(CrashLog.noticeDue(10L, 20L))
        val dir = newDir()
        assertNull(CrashLog.newestEpochMs(dir))
        assertNotNull(write(dir, 40L))
        assertNotNull(write(dir, 60L))
        assertEquals(60L, CrashLog.newestEpochMs(dir))
    }

    @Test fun credentialsInUrlsAreRedacted() {
        val dir = newDir()
        val cause = IllegalStateException("stream failed for http://host/get.php?username=u&password=p")
        val file = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("playback crashed http://host/get.php?username=u&password=p", cause), dir,
                appInfo = "1.0 (build 1)", deviceInfo = "M", androidInfo = "14", threadName = "main", nowMs = 5_000L,
            ),
        )
        val text = file.readText()
        assertContains(text, "password=***")
        assertContains(text, "username=***")
        assertContains(text, "Caused by: java.lang.IllegalStateException: stream failed for http://host/get.php?username=***&password=***")
        assertFalse(text.contains("password=p"))
        assertFalse(text.contains("username=u"))
        // Plex-style token too.
        val plex = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("plex http://h:32400/library?X-Plex-Token=SECRET123"), dir,
                appInfo = "1.0 (build 1)", deviceInfo = "M", androidInfo = "14", threadName = "main", nowMs = 6_000L,
            ),
        )
        assertContains(plex.readText(), "X-Plex-Token=***")
        assertFalse(plex.readText().contains("SECRET123"))
    }

    @Test fun xtreamPathCredentialsAreRedacted() {
        val dir = newDir()
        val file = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("GET http://srv/live/abc123/s3cr3t/1234.ts failed"), dir,
                appInfo = "1.0 (build 1)", deviceInfo = "M", androidInfo = "14", threadName = "main", nowMs = 7_000L,
            ),
        )
        val text = file.readText()
        assertContains(text, "/live/***/***/")
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("s3cr3t"))
    }

    @Test fun reportFormatAndParsing() = runBlocking {
        val dir = newDir()
        val file = assertNotNull(
            CrashLog.writeReport(
                RuntimeException("top message", IllegalStateException("inner cause")), dir,
                appInfo = "2.3.1 (build 42)", deviceInfo = "Sony BRAVIA", androidInfo = "12", threadName = "DefaultDispatcher-worker-1",
                nowMs = 1_725_000_000_000L,
            ),
        )
        val text = file.readText()
        val lines = text.lines()
        assertEquals("Omniverse crash report", lines[0])
        assertEquals("time: 1725000000000", lines[1])
        assertEquals("app: 2.3.1 (build 42)", lines[2])
        assertEquals("android: 12", lines[3])
        assertEquals("device: Sony BRAVIA", lines[4])
        assertEquals("thread: DefaultDispatcher-worker-1", lines[5])
        assertEquals(CrashLog.TRACE_MARK, lines[6])
        assertContains(lines[7], "java.lang.RuntimeException: top message")
        assertContains(text, "Caused by: java.lang.IllegalStateException: inner cause")

        assertEquals(1_725_000_000_000L, CrashLog.crashEpochMs(file.name))
        assertNull(CrashLog.crashEpochMs("notes.txt"))
        assertNull(CrashLog.crashEpochMs("crash-abc.txt"))
        assertEquals("java.lang.RuntimeException: top message", CrashLog.summary(text))

        val infos = CrashLog.list(dir)
        assertEquals(1, infos.size)
        assertEquals(file.name, infos[0].name)
        assertEquals(1_725_000_000_000L, infos[0].epochMs)
        assertEquals("java.lang.RuntimeException: top message", infos[0].summary)
        assertEquals(text, CrashLog.read(dir, file.name))
    }

    @Test fun listSortsNewestFirstAndDeleteAllClears() = runBlocking {
        val dir = newDir()
        assertNotNull(write(dir, 10L))
        assertNotNull(write(dir, 30L))
        assertNotNull(write(dir, 20L))
        val infos = CrashLog.list(dir)
        assertEquals(listOf(30L, 20L, 10L), infos.map { it.epochMs })
        assertTrue(CrashLog.flagExists(dir))
        assertEquals(4, CrashLog.deleteAll(dir)) // three reports + the flag
        assertTrue(CrashLog.list(dir).isEmpty())
        assertFalse(CrashLog.flagExists(dir))
    }

    @Test fun crashFlagIsCreatedAndSurvivesRotation() {
        val dir = newDir()
        assertFalse(CrashLog.flagExists(dir))
        assertNotNull(write(dir, 11L))
        assertTrue(CrashLog.flagExists(dir))
        for (i in 12..30) assertNotNull(write(dir, i.toLong()))
        assertTrue(CrashLog.flagExists(dir))
        CrashLog.deleteFlag(dir)
        assertFalse(CrashLog.flagExists(dir))
    }
}
