package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId

/** Broad sport families used for filter chips and artwork tint. */
enum class Sport(val label: String) {
    FOOTBALL("Football"), SOCCER("Soccer"), BASKETBALL("Basketball"), BASEBALL("Baseball"),
    HOCKEY("Hockey"), FIGHTING("Fight Night"), MOTORSPORT("Motorsport"), TENNIS("Tennis"),
    GOLF("Golf"), CRICKET("Cricket"), RUGBY("Rugby"), OTHER("More sports"),
}

/** What kind of airing it is: an actual game, or studio/news coverage around games. */
enum class SportsKind { EVENT, COVERAGE }

/** One sports airing from any source's guide, already resolved to the channel that carries it. */
data class SportsAiring(
    val channel: ContentKey,
    val categoryId: RemoteId,
    val channelName: String,
    val logoUrl: String?,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val sport: Sport,
    val kind: SportsKind,
    /** "Home" and "Away" when the title is a matchup ("A vs B", "A @ B"), else null. */
    val teams: Pair<String, String>?,
    /** League/competition name found in the text (NFL, Premier League…), if any. */
    val league: String?,
    /** Programme artwork from the guide (often a matchup or event card), when the provider sends one. */
    val imageUrl: String? = null,
) {
    fun isLive(nowMs: Long) = nowMs in startMs until endMs
}

/**
 * Pure text heuristics over guide data. Deliberately conservative: a programme only counts
 * as sports when the guide category says so, the channel is a sports channel, or the title is
 * an explicit matchup / names a league. Never fetches anything.
 */
object SportsClassifier {
    private val matchup = Regex("""^(.{2,60}?)\s+(?:vs\.?|v\.?|@|at)\s+(.{2,60}?)(?:\s*[-:|(].*)?$""", RegexOption.IGNORE_CASE)
    private val coverageWords = Regex("""\b(news|center|centre|tonight|live!?$|daily|highlights|preview|review|recap|analysis|countdown|show|talk|podcast|magazine|report|zone|extra|now)\b""", RegexOption.IGNORE_CASE)
    private val sportyText = Regex("""\b(sport|sports|espn|tsn|sky ?sports|bein|dazn|fox ?sports|nbc ?sports|cbs ?sports|eurosport|bt ?sport|tnt ?sports|supersport|arena ?sport|sportsnet|match|game ?day|league|cup|championship|grand prix|ufc|wwe|boxing|nfl|nba|mlb|nhl|mls|ncaa|f1|nascar|pga|atp|wta|ipl|fifa|uefa)\b""", RegexOption.IGNORE_CASE)

    private val leagues = listOf(
        "NFL", "NCAA Football", "College Football", "NBA", "WNBA", "NCAA Basketball", "MLB", "NHL", "MLS",
        "Premier League", "Champions League", "Europa League", "La Liga", "Serie A", "Bundesliga", "Ligue 1",
        "FA Cup", "World Cup", "UEFA", "UFC", "WWE", "Formula 1", "F1", "NASCAR", "IndyCar", "MotoGP",
        "PGA", "LPGA", "ATP", "WTA", "Wimbledon", "US Open", "IPL", "Six Nations", "Super Rugby",
    )

    private val sportWords: List<Pair<Sport, Regex>> = listOf(
        Sport.FOOTBALL to Regex("""\b(nfl|ncaaf|college football|american football|super bowl|touchdown)\b""", RegexOption.IGNORE_CASE),
        Sport.SOCCER to Regex("""\b(soccer|premier league|champions league|europa|la liga|serie a|bundesliga|ligue 1|mls|fa cup|uefa|fifa|world cup|football club|fc|united|city fc)\b""", RegexOption.IGNORE_CASE),
        Sport.BASKETBALL to Regex("""\b(nba|wnba|basketball|ncaab|march madness)\b""", RegexOption.IGNORE_CASE),
        Sport.BASEBALL to Regex("""\b(mlb|baseball|world series)\b""", RegexOption.IGNORE_CASE),
        Sport.HOCKEY to Regex("""\b(nhl|hockey|stanley cup)\b""", RegexOption.IGNORE_CASE),
        Sport.FIGHTING to Regex("""\b(ufc|boxing|mma|wwe|wrestling|bellator|fight night|pfl)\b""", RegexOption.IGNORE_CASE),
        Sport.MOTORSPORT to Regex("""\b(f1|formula 1|formula one|grand prix|nascar|indycar|motogp|rally|racing)\b""", RegexOption.IGNORE_CASE),
        Sport.TENNIS to Regex("""\b(tennis|atp|wta|wimbledon|us open|roland garros)\b""", RegexOption.IGNORE_CASE),
        Sport.GOLF to Regex("""\b(golf|pga|lpga|masters|ryder cup)\b""", RegexOption.IGNORE_CASE),
        Sport.CRICKET to Regex("""\b(cricket|ipl|test match|t20|odi)\b""", RegexOption.IGNORE_CASE),
        Sport.RUGBY to Regex("""\b(rugby|six nations|super rugby)\b""", RegexOption.IGNORE_CASE),
    )

    private val idleChannel = Regex("""\b(no (event|events|game|games|stream|broadcast|live event)s?|off[ -]?air|offline|event (ended|over)|coming soon)\b""", RegexOption.IGNORE_CASE)

    /** IPTV event channels that are idle say so in their NAME ("Paramount+ 99: NO EVENT"): never "live now". */
    fun isIdleChannel(name: String): Boolean = idleChannel.containsMatchIn(name)

    /** True for a channel or category that is clearly a sports outlet ("US: Sports HD", "ESPN 2"). */
    fun isSportsChannel(name: String, categoryName: String?): Boolean =
        sportyText.containsMatchIn(name) || (categoryName != null && sportyText.containsMatchIn(categoryName))

    /**
     * Classifies one programme, or returns null when it is not sports. [guideCategory] is the EPG's
     * own genre ("Sports", "Football"…), the strongest signal when providers fill it in.
     */
    fun classify(title: String, description: String?, guideCategory: String?, channelName: String, channelCategory: String?): Classified? {
        // Providers fill idle team/event channels with filler ("No Game Today", "Off Air"): never sports content.
        if (placeholder.containsMatchIn(title.trim())) return null
        val text = listOfNotNull(title, guideCategory, description?.take(160)).joinToString(" ")
        val m = matchup.find(stripPrefix(title.trim()))
        val teams = m?.let { it.groupValues[1].trim() to cleanTeam(it.groupValues[2]) }?.takeIf { (a, b) ->
            a.any(Char::isLetter) && b.any(Char::isLetter) && !coverageWords.containsMatchIn(a)
        }
        // Two recognised pro teams make it a game even without a sports genre or channel.
        val known = teams?.let { (a, b) -> Teams.find(a)?.let { ta -> Teams.find(b, ta.league)?.let { ta to it } } }
        val league = leagues.firstOrNull { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
            ?: known?.first?.league
        val guideSays = guideCategory != null && Regex("""sport|football|soccer|basketball|baseball|hockey|tennis|golf|racing|boxing|cricket|rugby""", RegexOption.IGNORE_CASE).containsMatchIn(guideCategory)
        val sportsChannel = isSportsChannel(channelName, channelCategory)
        if (!guideSays && !sportsChannel && teams == null && league == null) return null
        val sport = known?.first?.sport ?: sportWords.firstOrNull { (_, r) -> r.containsMatchIn(text) }?.first
            ?: sportWords.firstOrNull { (_, r) -> r.containsMatchIn(channelName + " " + (channelCategory ?: "")) }?.first
            ?: Sport.OTHER
        val kind = if (teams != null || (league != null && !coverageWords.containsMatchIn(title))) SportsKind.EVENT else SportsKind.COVERAGE
        // A lone "vs" outside a sports context (a film titled "Kramer vs. Kramer") needs a sports signal.
        if (!guideSays && !sportsChannel && league == null) return null
        return Classified(sport, kind, teams, league)
    }

    /** Filler titles on idle event/team channels. */
    private val placeholder = Regex("""^(no (game|games|event|events|match|broadcast|programming|program|programme|live event)s?\b|off[ -]?air\b|channel (is )?(off|closed)|event (will )?(start|begin)|stay tuned|tba\b|to be announced|coming soon|closed\b|offline\b|nothing (on|scheduled))""", RegexOption.IGNORE_CASE)

    /** "NFL 03: Browns vs Steelers" / "US| ESPN+ 12 | Browns @ Steelers" → the part after the channel prefix. */
    private fun stripPrefix(s: String): String {
        // Only a short leading label ("NFL 03:", "US| ESPN+ 12 |") — never a clock's colon.
        val tail = s.replaceFirst(Regex("""^(?:[^:|]{1,30}[:|]\s*){1,2}"""), "").trim()
        return if (tail != s && tail.length >= 5 && matchup.containsMatchIn(tail)) tail else s
    }

    /** Drops a trailing kick-off time and tags from the away side ("Steelers 1:00 PM ET", "Steelers (HD)"). */
    private fun cleanTeam(s: String): String =
        s.replace(Regex("""\s*(\(.*?\)|\[.*?]|\b\d{1,2}(:\d{2})?\s*(am|pm)\b.*|\b\d{1,2}:\d{2}\b.*|\b\d{1,2}$)""", RegexOption.IGNORE_CASE), "").trim()

    /** IPTV "event channels" carry the game in the channel NAME ("NFL 03: Browns vs Steelers 1:00 PM ET"). */
    fun classifyEventChannel(channelName: String, categoryName: String?): Classified? {
        if (placeholder.containsMatchIn(stripPrefix(channelName))) return null
        val c = classify(channelName, null, null, channelName, categoryName) ?: return null
        return c.takeIf { it.kind == SportsKind.EVENT && it.teams != null }
    }

    private val clockRe = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)

    /** Minutes after local midnight for a "1:00 PM" style time in [text], or null. */
    fun clockMinutes(text: String): Int? {
        val m = clockRe.find(text) ?: return null
        val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].equals("pm", true)) 12 else 0
        return h * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    data class Classified(val sport: Sport, val kind: SportsKind, val teams: Pair<String, String>?, val league: String?)
}
