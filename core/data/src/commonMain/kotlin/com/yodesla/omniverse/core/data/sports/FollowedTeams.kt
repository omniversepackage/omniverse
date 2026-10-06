package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.data.UserDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A team the viewer follows, in one profile. [key] is the stable identity: league-qualified when the
 * team is a recognised pro club (`l:NFL|chiefs`), the plain display name when it is not (`n:riverton`).
 */
data class FollowedTeam(val key: String, val name: String, val league: String?)

/**
 * Task 103: followed teams, the "hide sports scores" switch, and the pure matching that both rely on.
 * Everything here is text-only — no feed, no database — so the same rules decide what a card shows on
 * the Sports page and what the "My teams" rows on Sports and Home contain.
 */
object FollowedTeams {
    /** Profile-scoped settings keys (both are listed in UserDataRepositoryImpl.isProfileSetting). */
    const val KEY = "sports_followed_teams"
    const val HIDE_SCORES_KEY = "sports_hide_scores"

    /** What a card shows in place of the score while scores are hidden and not yet revealed. */
    const val HIDDEN_TEXT = "Score hidden"

    private const val FIELD = '\u001F'
    const val NAME_PREFIX = "n:"
    const val LEAGUE_PREFIX = "l:"

    /** Lower-case, punctuation-free form of a display name: the identity an unknown team is stored under. */
    fun normalize(name: String): String = name.lowercase()
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""[^a-z0-9 ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun keyOf(t: Team) = LEAGUE_PREFIX + t.league + "|" +
        t.nickname.removeSuffix(" MLB").removeSuffix(" NHL").lowercase()

    /** "English Premier League" and "Premier League" are the same competition for matching purposes. */
    private fun leagueMatches(hint: String, league: String): Boolean {
        val h = hint.lowercase().trim()
        val l = league.lowercase().trim()
        return h == l || h.contains(l) || l.contains(h)
    }

    /**
     * The keys that could stand for [name]. With a [leagueHint] only a team actually in that league
     * counts, so an NFL game never matches a followed MLB team that shares its nickname (Cardinals,
     * Rangers, Jets…); without one, the best match plus the plain name are both offered.
     */
    fun candidateKeys(name: String, leagueHint: String? = null): Set<String> {
        val plain = normalize(name)
        if (plain.isEmpty()) return emptySet()
        val team = if (leagueHint != null) Teams.find(name, leagueHint)?.takeIf { leagueMatches(leagueHint, it.league) } else Teams.find(name)
        return setOfNotNull(team?.let(::keyOf), NAME_PREFIX + plain)
    }

    /** [inner]'s words appear in [outer] in order, so "Alabama" and "Alabama Crimson Tide" agree. */
    private fun containsWords(outer: String, inner: String): Boolean {
        val x = outer.split(' ').filter { it.isNotEmpty() }
        val y = inner.split(' ').filter { it.isNotEmpty() }
        if (y.isEmpty() || y.size > x.size || y.joinToString("").length < 4) return false
        return (0..x.size - y.size).any { i -> x.subList(i, i + y.size) == y }
    }

    private fun nameMatch(a: String, b: String): Boolean = containsWords(a, b) || containsWords(b, a)

    /**
     * Does this followed team's own identity appear in [plain]? A guide title names both sides
     * ("Broncos at Chiefs"), so the team we are looking for is found by its own nickname rather than
     * by whichever team the text happens to mention first.
     */
    private fun keyMatches(f: FollowedTeam, plain: String, leagueHint: String?): Boolean = when {
        f.key.startsWith(LEAGUE_PREFIX) -> {
            val body = f.key.removePrefix(LEAGUE_PREFIX)
            (leagueHint == null || leagueMatches(leagueHint, body.substringBefore('|'))) && containsWords(plain, body.substringAfter('|'))
        }
        f.key.startsWith(NAME_PREFIX) -> nameMatch(f.key.removePrefix(NAME_PREFIX), plain)
        else -> false
    }

    /** The followed team [name] refers to, or null. [leagueHint] is the game's or airing's competition. */
    fun match(followed: List<FollowedTeam>, name: String, leagueHint: String? = null): FollowedTeam? {
        val keys = candidateKeys(name, leagueHint)
        if (keys.isEmpty()) return null
        followed.firstOrNull { it.key in keys }?.let { return it }
        val plain = normalize(name)
        return followed.firstOrNull { keyMatches(it, plain, leagueHint) }
    }

    /** The followed team a scheduled game involves (checked on full names, then short names). */
    fun match(followed: List<FollowedTeam>, g: ScheduledGame): FollowedTeam? =
        match(followed, g.home.name, g.league) ?: match(followed, g.away.name, g.league)
            ?: match(followed, g.home.shortName, g.league) ?: match(followed, g.away.shortName, g.league)

    /** The followed team a guide airing names (its matchup first, else its title). */
    fun match(followed: List<FollowedTeam>, a: SportsAiring): FollowedTeam? =
        a.teams?.let { match(followed, it.first, a.league) ?: match(followed, it.second, a.league) }
            ?: match(followed, a.title, a.league)

    /** One team per line, fields split by [FIELD]; control characters can never corrupt the list. */
    fun encode(teams: List<FollowedTeam>): String = teams.joinToString("\n") { t ->
        listOf(t.key, clean(t.name), t.league.orEmpty()).joinToString(FIELD.toString())
    }

    fun decode(raw: String?): List<FollowedTeam> = raw.orEmpty().lineSequence()
        .map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val f = line.split(FIELD)
            val key = f.getOrElse(0) { "" }.trim()
            if (!key.startsWith(NAME_PREFIX) && !key.startsWith(LEAGUE_PREFIX)) return@mapNotNull null
            val name = f.getOrElse(1) { "" }.trim().ifEmpty { displayOf(key) }
            FollowedTeam(key, name, f.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
        }.toList()

    private fun clean(s: String): String = s.replace(Regex("""[\u0000-\u001F\u007F]"""), " ").trim().take(80)

    private fun displayOf(key: String): String =
        if (key.startsWith(LEAGUE_PREFIX)) key.substringAfter('|') else key.removePrefix(NAME_PREFIX)

    /** A card's score line: the real one, or [HIDDEN_TEXT] until the viewer reveals it with OK. */
    fun scoreText(away: String?, home: String?, hidden: Boolean, revealed: Boolean, separator: String = "-"): String =
        if (hidden && !revealed) HIDDEN_TEXT else "${away ?: "0"}$separator${home ?: "0"}"

    /** Feed status lines carry scores themselves ("Final 24-17"): blank those while hidden too. Clocks ("8:15 PM") are left alone. */
    fun detailText(detail: String, hidden: Boolean, revealed: Boolean): String =
        if (hidden && !revealed && scoreInText.containsMatchIn(detail)) HIDDEN_TEXT else detail

    private val scoreInText = Regex("""(?<!\d)\d{1,3}\s*[-–]\s*\d{1,3}(?!\d)""")
}

/** Followed teams and the score switch for the active profile, on the settings table. */
class FollowedTeamsStore(private val userData: UserDataRepository) {
    val teams: Flow<List<FollowedTeam>> = userData.setting(FollowedTeams.KEY).map(FollowedTeams::decode)
    val hideScores: Flow<Boolean> = userData.setting(FollowedTeams.HIDE_SCORES_KEY).map { it == "1" || it == "true" }

    suspend fun current(): List<FollowedTeam> = teams.first()

    /** Follow [name] (or stop following it if already followed); returns true when it is now followed. */
    suspend fun toggle(name: String, leagueHint: String? = null): Boolean {
        val list = current()
        val existing = FollowedTeams.match(list, name, leagueHint)
        val next = if (existing != null) list.filter { it.key != existing.key }
        else list + FollowedTeam(
            key = FollowedTeams.candidateKeys(name, leagueHint).firstOrNull { it.startsWith(FollowedTeams.LEAGUE_PREFIX) }
                ?: (FollowedTeams.NAME_PREFIX + FollowedTeams.normalize(name)),
            name = name.trim(),
            league = leagueHint?.trim()?.takeIf { it.isNotEmpty() },
        )
        userData.putSetting(FollowedTeams.KEY, FollowedTeams.encode(next))
        return existing == null
    }

    suspend fun setHideScores(hide: Boolean) {
        userData.putSetting(FollowedTeams.HIDE_SCORES_KEY, if (hide) "1" else "0")
    }
}
