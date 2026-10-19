package com.yodesla.omniverse.core.data.sports

/**
 * "Where to watch" used to match a game only by its broadcast NETWORK ("CBS", "ESPN", "NFL Network").
 * A channel dedicated to one of the clubs ("Cleveland Browns", "NFL: Browns 24/7", "Man Utd TV") has no
 * network name in it, so it never matched and was missing from every game of that team.
 *
 * This matches channels by the CLUBS playing instead, for every league, and scores a dedicated
 * team channel above any network channel so it is listed first.
 */
object TeamChannelMatcher {
    private val leagueTokens = listOf("nfl", "nba", "mlb", "nhl", "mls", "wnba")

    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun hasPhrase(text: String, phrase: String): Boolean =
        phrase.isNotEmpty() && " $text ".contains(" $phrase ")

    private fun nickname(team: GameTeam, league: String): String? =
        Teams.find(team.name, league)?.nickname?.removeSuffix(" MLB")?.removeSuffix(" NHL")?.lowercase()

    /** Channel-name search terms for a club: the full name, its nickname, the feed's short name. */
    fun termsFor(team: GameTeam, league: String): List<String> =
        listOfNotNull(norm(team.name), nickname(team, league)?.let(::norm), norm(team.shortName))
            .filter { it.length >= 4 }.distinct()

    /**
     * Higher is better; 0 means the channel is not about this club. Full club name = dedicated
     * channel. Nickname only ("Browns") must not belong to another team or another league
     * (an MLB "Giants" channel is not the NFL Giants).
     */
    fun score(channelName: String, team: GameTeam, league: String): Int {
        val name = norm(channelName)
        if (name.isEmpty()) return 0
        val full = norm(team.name)
        val nick = nickname(team, league)?.let(::norm)
        val short = norm(team.shortName).takeIf { it.length >= 4 }
        val strong = hasPhrase(name, full)
        val weak = !strong && (nick?.let { hasPhrase(name, it) } == true || short?.let { hasPhrase(name, it) } == true)
        if (!strong && !weak) return 0
        val other = leagueTokens.filter { it != league.lowercase() && hasPhrase(name, it) }
        if (other.isNotEmpty() && !hasPhrase(name, league.lowercase())) return 0
        // Radio feeds are audio only: not a place to WATCH.
        if (hasPhrase(name, "radio")) return 0
        // Nickname-only needs the league named too: "24/7 Mrs Browns Boys" is not the Browns.
        if (weak && !hasPhrase(name, league.lowercase())) return 0
        if (weak) {
            val found = Teams.find(channelName, league)
            if (found != null) {
                if (!found.nickname.removeSuffix(" MLB").removeSuffix(" NHL").equals(nick ?: "", ignoreCase = true)) return 0
            } else if ((short?.length ?: 0) < 6) return 0 // an unknown short word ("Utd", "City") is too ambiguous
        }
        var s = if (strong) 60 else 50
        if (Regex("""^(us|usa)\b|\b(us|usa)[:| ]""").containsMatchIn(channelName.lowercase())) s += 6
        if (Regex("""^(es|mx|ar|br|pt|fr|de|it|uk|ca|au|nl|in|latino|spanish|espanol|español)\b|\b(spanish|español|latino|deportes)\b""")
                .containsMatchIn(channelName.lowercase())) s -= 7
        if (Regex("""\b(4k|uhd|fhd|hd)\b""").containsMatchIn(name)) s += 1
        return s
    }
}
