package com.yodesla.omniverse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactTest {
    @Test
    fun masksXtreamPathCredentials() {
        assertEquals(
            "http://h:8080/live/***/***/1001.ts",
            Redact.text("http://h:8080/live/alice/s3cret/1001.ts"),
        )
        assertEquals(
            "http://h/timeshift/***/***/60/2026-09-26:12-00/5.ts",
            Redact.text("http://h/timeshift/alice/s3cret/60/2026-09-26:12-00/5.ts"),
        )
    }

    @Test
    fun masksQueryCredentials() {
        val out = Redact.text("http://h/player_api.php?username=alice&password=s3cret&action=get_live_streams")
        assertEquals("http://h/player_api.php?username=***&password=***&action=get_live_streams", out)
        assertFalse("s3cret" in Redact.text("GET /x?X-Plex-Token=s3cret failed"))
    }

    @Test
    fun masksCredentialKeysOutsideUrls() {
        assertEquals("token=*** expired", Redact.text("token=SECRET123 expired"))
        assertEquals("password=*** rejected for user=***", Redact.text("password=hunter2 rejected for user=kory"))
        // A key that is part of a longer word is not a credential.
        assertEquals("mypassword=keep", Redact.text("mypassword=keep"))
    }

    @Test
    fun masksUrlUserInfo() {
        val out = Redact.text("http://alice:s3cret@tv.example.com:8080/playlist.m3u8")
        assertEquals("http://***:***@tv.example.com:8080/playlist.m3u8", out)
        assertFalse("alice" in out)
        assertFalse("s3cret" in out)
        assertEquals("http://***:***@tv.example.com:8080/x?token=***", Redact.text("http://alice:s3cret@tv.example.com:8080/x?token=abc"))
    }

    @Test
    fun masksBareUserInfoButNotTimesOrMail() {
        assertEquals("proxy ***:***@host", Redact.text("proxy alice:s3cret@host"))
        assertEquals("12:30@ is a time, not a credential", Redact.text("12:30@ is a time, not a credential"))
        assertEquals("mail kory@example.com", Redact.text("mail kory@example.com"))
    }

    @Test
    fun leavesOtherTextAlone() {
        assertEquals("http://h/logos/1001.png", Redact.text("http://h/logos/1001.png"))
    }

    @Test
    fun masksOAuthTokenKeys() {
        assertEquals("access_token=***", Redact.text("access_token=SECRET123"))
        assertEquals("refresh_token=***", Redact.text("refresh_token=SECRET123"))
        assertEquals("id_token=***", Redact.text("id_token=SECRET123"))
        assertEquals("?access_token=***&refresh_token=***", Redact.text("?access_token=abc&refresh_token=def"))
        assertEquals("apikey=***", Redact.text("apikey=SECRET123"))
        assertEquals("api_key=***", Redact.text("api_key=SECRET123"))
    }

    @Test
    fun masksJsonSecretFields() {
        assertEquals(
            "{\"password\":\"***\",\"token\":\"***\"}",
            Redact.text("{\"password\":\"hunter2\",\"token\":\"tok123\"}"),
        )
        assertEquals(
            "{\"access_token\":\"***\",\"refresh_token\":\"***\",\"id_token\":\"***\"}",
            Redact.text("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"id_token\":\"i\"}"),
        )
        assertEquals(
            "{\"api_key\":\"***\"}",
            Redact.text("{\"api_key\":\"abc\"}"),
        )
        // A key that is part of a longer word is not a credential.
        assertEquals("{\"mypassword\":\"keep\"}", Redact.text("{\"mypassword\":\"keep\"}"))
    }

    @Test
    fun masksBearerAuthHeaders() {
        assertEquals("Authorization: Bearer ***", Redact.text("Authorization: Bearer abc.def-123"))
        assertEquals("Bearer ***", Redact.text("Bearer abc.def-123"))
        assertFalse("abc.def-123" in Redact.text("Authorization: Bearer abc.def-123"))
    }

    @Test
    fun timeWindowContains() {
        val w = TimeWindow(10, 20)
        assert(15L in w)
        assertFalse(20L in w)
    }
}
