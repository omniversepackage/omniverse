package com.yodesla.omniverse.feature.home.settings

import androidx.compose.runtime.Immutable

/**
 * Task 99: the Settings search index. A small static table of every reachable setting (label +
 * keywords) mapped to its category and pane row key, plus the pure matching function. Labels are
 * the English strings the panes show; row keys are the pane item keys (see [settingsPaneItemKeys])
 * so a hit can be scrolled to and focused. No Compose, no resources - unit-testable as-is.
 */
@Immutable
data class SettingsSearchEntry(
    val category: SettingsCategory,
    val rowKey: String,
    val label: String,
    val keywords: List<String>,
)

/** Every built-in row plus the slot-provided ones (Parental, Customize Home, updates, the
 *  phone-remote and backup panes), in pane order. Keywords cover the words a viewer types. */
val settingsSearchIndex: List<SettingsSearchEntry> = listOf(
    // Sources
    SettingsSearchEntry(SettingsCategory.Sources, SettingsRowKeys.CATEGORY_LANG, "Category languages", listOf("language", "languages", "country", "region", "arabic", "english", "spanish", "french", "german", "filter", "categories", "live")),
    SettingsSearchEntry(SettingsCategory.Sources, SettingsRowKeys.CATEGORY_LANG_UNTAGGED, "Show categories without a language tag", listOf("language", "languages", "untagged", "news", "sports", "4k", "categories", "filter")),
    SettingsSearchEntry(SettingsCategory.Sources, SettingsRowKeys.CATEGORY_LANG_VOD, "Also filter Movies & Shows", listOf("language", "languages", "filter", "movies", "shows", "vod", "categories")),
    SettingsSearchEntry(SettingsCategory.Sources, SettingsRowKeys.HIDDEN_CATEGORIES, "Hidden categories", listOf("hidden", "categories", "show", "restore", "unhide")),
    SettingsSearchEntry(SettingsCategory.Sources, SettingsRowKeys.ADD_SOURCE, "Add a source", listOf("add", "source", "sources", "provider", "account", "login", "iptv", "plex", "xtream", "m3u", "url", "connection")),
    // Playback
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.AUDIO, "Preferred audio language", listOf("audio", "language", "languages", "track", "tracks", "dub", "dubbed", "sound")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.SUBTITLES, "Subtitles", listOf("subtitle", "subtitles", "captions", "caption", "subs", "cc", "forced")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.SUBTITLE_STYLE, "Subtitle style", listOf("subtitle", "subtitles", "captions", "style", "size", "color", "colour", "background", "position", "font")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.LOCAL_SKIP, "On-device skip suggestions", listOf("skip", "intro", "intros", "credits", "local", "on-device", "suggestions")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.COMMUNITY_SKIP, "Online intro and credits timestamps", listOf("skip", "intro", "intros", "credits", "timestamps", "online", "theintrodb", "tvmaze")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.TVMAZE_CREDIT, "TVmaze data source", listOf("tvmaze", "data", "source", "credit", "attribution")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.INTRO, "Intros", listOf("intro", "intros", "skip", "opening", "auto")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.CREDITS, "Credits", listOf("credits", "ending", "outro", "skip", "auto")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.FRAME_RATE, "Match frame rate", listOf("frame", "rate", "framerate", "fps", "hz", "refresh", "motion", "flicker")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.PRELOAD_NEXT_CHANNEL, "Pre-load next channel", listOf("preload", "pre-load", "next", "channel", "channels", "live", "zap", "zapping", "buffer", "instant")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.SPOILER_FREE, "Hide spoilers for unwatched episodes", listOf("spoiler", "spoilers", "hide", "episode", "episodes", "description", "thumbnail")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.SPOILER_FREE_HIDE_TITLES, "Also hide episode titles", listOf("spoiler", "spoilers", "titles", "episode", "episodes", "hide")),
    SettingsSearchEntry(SettingsCategory.Playback, SettingsRowKeys.BACK_FROM_SEARCH, "Back from a search result goes to", listOf("back", "search", "searching", "result", "results", "guide", "details", "detail", "navigation", "recent", "history")),
    // Display
    SettingsSearchEntry(SettingsCategory.Display, SettingsRowKeys.EXPERIENCE, "Experience", listOf("experience", "simple", "full", "mode", "interface")),
    SettingsSearchEntry(SettingsCategory.Display, SettingsRowKeys.TEXT_SIZE, "Text size", listOf("text", "size", "font", "fonts", "zoom", "big", "large", "bigger", "accessibility")),
    SettingsSearchEntry(SettingsCategory.Display, SettingsRowKeys.SCREENSAVER, "Screensaver", listOf("screensaver", "screen", "saver", "idle", "burn", "artwork", "clock")),
    SettingsSearchEntry(SettingsCategory.Display, SettingsRowKeys.CUSTOMIZE_HOME, "Customize Home", listOf("home", "rows", "customize", "layout", "reorder", "posters", "order")),
    SettingsSearchEntry(SettingsCategory.Display, SettingsRowKeys.HIDDEN_CW, "Hidden from Continue Watching", listOf("continue", "watching", "hidden", "hide", "restore", "dismissed")),
    // Profiles & kids
    SettingsSearchEntry(SettingsCategory.ProfilesAndKids, SettingsRowKeys.MANAGE, "Manage profiles", listOf("profile", "profiles", "user", "users", "add", "delete", "remove", "guest", "kids")),
    SettingsSearchEntry(SettingsCategory.ProfilesAndKids, SettingsRowKeys.CREATE_PROFILE, "Create a profile", listOf("profile", "profiles", "create", "new", "add", "guest")),
    SettingsSearchEntry(SettingsCategory.ProfilesAndKids, SettingsRowKeys.PICKER, "Ask who's watching at startup", listOf("profile", "profiles", "startup", "start", "who", "watching", "picker", "switch")),
    SettingsSearchEntry(SettingsCategory.ProfilesAndKids, SettingsRowKeys.PARENTAL, "Parental controls", listOf("pin", "password", "passcode", "parental", "parent", "kids", "child", "children", "lock", "locked", "mature", "adult", "content", "rating")),
    // Metadata
    SettingsSearchEntry(SettingsCategory.Metadata, SettingsRowKeys.WIKIDATA, "Fill missing plot and cast from Wikidata", listOf("metadata", "plot", "cast", "wikidata", "wikipedia", "tmdb", "details")),
    SettingsSearchEntry(SettingsCategory.Metadata, SettingsRowKeys.RT, "Rotten Tomatoes scores", listOf("rotten", "tomatoes", "scores", "rating", "ratings", "critics", "audience")),
    // Slot-only categories: the whole pane is the setting.
    SettingsSearchEntry(SettingsCategory.PhoneRemote, SettingsRowKeys.SLOT, "Phone remote", listOf("phone", "remote", "mobile", "app", "second screen", "controller", "qr", "pair")),
    SettingsSearchEntry(SettingsCategory.BackupRestore, SettingsRowKeys.SLOT, "Backup & restore", listOf("backup", "restore", "export", "import", "sync", "transfer")),
    // About & updates
    SettingsSearchEntry(SettingsCategory.AboutAndUpdates, SettingsRowKeys.VERSION, "Version", listOf("version", "build", "about", "info")),
    SettingsSearchEntry(SettingsCategory.AboutAndUpdates, SettingsRowKeys.UPDATE_CHECK, "Check for updates", listOf("update", "updates", "check", "new", "version", "install")),
    SettingsSearchEntry(SettingsCategory.AboutAndUpdates, SettingsRowKeys.LEGAL, "Legal & attributions", listOf("legal", "attribution", "attributions", "tmdb", "license", "privacy", "disclaimer")),
)

private data class Scored(val pos: Int, val entry: SettingsSearchEntry, val labelStart: Boolean, val labelAll: Boolean)

/**
 * Filter the index with a typed or voice query. Every whitespace-separated token must hit the
 * entry's label (case-insensitive substring) or one of its keywords (prefix either way, so
 * "subtit" finds Subtitles but "pin" does not hit "zapping"); a blank query has no results.
 * Label matches rank above keyword-only matches, then index order, so 'subtitles' lands on
 * Subtitles rather than on every row that merely mentions subtitles.
 */
/**
 * Task 111 M5: a search hit can only be jumped to when its row is actually rendered in its pane.
 * The pane's rendered keys come from settingsPaneItemKeys (conditional on spoiler-free, compact,
 * guest mode, slot, hidden rows). A hit whose key is absent (-1) must show a notice, not request
 * focus on a FocusRequester attached to nothing.
 */
fun settingsJumpIndex(renderedKeys: List<String>, jumpKey: String): Int = renderedKeys.indexOf(jumpKey)

fun searchSettings(query: String, index: List<SettingsSearchEntry> = settingsSearchIndex): List<SettingsSearchEntry> {
    val tokens = query.lowercase().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return index.mapIndexed { pos, e ->
        val label = e.label.lowercase()
        val kws = e.keywords.map { it.lowercase() }
        val matched = tokens.all { t -> t in label || kws.any { k -> k.startsWith(t) || t.startsWith(k) } }
        if (!matched) return@mapIndexed null
        Scored(pos, e, label.startsWith(tokens.first()), tokens.all { it in label })
    }.filterNotNull().sortedWith(
        compareByDescending<Scored> { it.labelStart }
            .thenByDescending { it.labelAll }
            .thenBy { it.pos },
    ).map { it.entry }
}
