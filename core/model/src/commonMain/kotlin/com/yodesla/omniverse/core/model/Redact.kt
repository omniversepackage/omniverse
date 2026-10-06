package com.yodesla.omniverse.core.model

/**
 * Masks credentials in any URL or message before it reaches a log, crash report or error text.
 * Handles query params (username=, password=, user=, pass=, token=, access_token=, refresh_token=,
 * id_token=, X-Plex-Token=, api_key=, apikey=), Xtream path style (/live/USER/PASS/..., /movie/...,
 * /series/..., /timeshift/...), the userinfo form of a URL (http://USER:PASS@host — task 104),
 * JSON bodies ({"password":"...","token":"..."} — task 111 M3) and Bearer auth headers.
 */
object Redact {
    /** A credential-bearing key only counts when it starts a token, so `?password=` and
     *  `password=` are masked while `mypassword=…` inside a longer word is left alone. The OAuth
     *  token keys are listed before the bare `token` so `access_token=` is matched whole rather
     *  than rejected by the leading-underscore boundary. */
    private val queryParam = Regex(
        "(?i)(^|[^A-Za-z0-9_.-])((?:username|password|x-plex-token|api_key|apikey|access_token|refresh_token|id_token|user|pass|token)=)[^&#\\s\"']*",
    )
    private val xtreamPath = Regex(
        "(?i)(/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+/",
    )
    /** `http://alice:s3cret@host` — the userinfo a proxy or IPTV URL carries before the host. */
    private val userInfo = Regex(
        "(?i)([a-z][a-z0-9+.-]*://)[^/?#@\\s:]+:[^/?#@\\s]+@",
    )
    /** Same thing written without a scheme: `proxy alice:s3cret@host`. The user must start with a
     *  letter so a timestamp like `12:30@` is not mistaken for credentials. The `[` inside the
     *  prefix class is escaped — java.util.regex reads an unescaped one as a nested class. */
    private val bareUserInfo = Regex(
        "(?i)(^|[\\s,;=({\\[])([a-z][^\\s:@/]*):[^\\s@/]+@",
    )
    /** A JSON body's secret field: `"password":"hunter2"`, `"token":"…"`, `"access_token":"…"`.
     *  The key must sit in its own quotes, so `"mypassword":"keep"` is left alone. */
    private val jsonSecret = Regex(
        "(?i)(\"(?:password|token|access_token|refresh_token|id_token|api_key|apikey)\"\\s*:\\s*\")[^\"]*(\")",
    )
    /** An `Authorization: Bearer <token>` header, or a bare `Bearer <token>`. */
    private val bearerHeader = Regex(
        "(?i)((?:authorization:\\s*)?bearer\\s+)[A-Za-z0-9+._\\-/=]+",
    )

    fun text(input: String): String =
        input.replace(queryParam) { it.groupValues[1] + it.groupValues[2] + "***" }
            .replace(xtreamPath) { it.groupValues[1] + "***/***/" }
            .replace(userInfo) { it.groupValues[1] + "***:***@" }
            .replace(bareUserInfo) { it.groupValues[1] + "***:***@" }
            .replace(jsonSecret) { it.groupValues[1] + "***" + it.groupValues[2] }
            .replace(bearerHeader) { it.groupValues[1] + "***" }
}
