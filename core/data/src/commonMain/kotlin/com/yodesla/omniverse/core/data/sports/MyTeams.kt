package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId

/** One place the viewer can watch a followed team's game: a channel in one of their own sources. */
data class MyTeamOption(val key: ContentKey, val categoryId: RemoteId, val name: String, val logoUrl: String?)

/**
 * A live or upcoming game involving a followed team, in the shape both "My teams" rows render:
 * the matchup, when it kicks off, whether it is on now, and where this viewer can watch it.
 */
data class MyTeamItem(
    val id: String,
    val title: String,
    val league: String,
    val sport: Sport,
    val startMs: Long,
    val state: GameState,
    val detail: String,
    val followed: FollowedTeam,
    val networks: List<String>,
    val imageUrl: String?,
    /** Empty when none of the viewer's channels carry it. */
    val options: List<MyTeamOption>,
)

/** Pure filtering and ordering for "My teams": what a followed team's schedule contains, and in what order. */
object MyTeams {
    /** Games from the public schedule that involve one of [followed]; live and upcoming only. */
    fun fromGames(games: List<ScheduledGame>, followed: List<FollowedTeam>, nowMs: Long, limit: Int = 20): List<MyTeamItem> {
        if (followed.isEmpty()) return emptyList()
        return sort(games.mapNotNull { g ->
            val team = FollowedTeams.match(followed, g) ?: return@mapNotNull null
            if (g.state == GameState.FINAL || g.startMs <= nowMs - 15 * 60_000L) return@mapNotNull null
            MyTeamItem(
                id = g.id,
                title = "${g.away.shortName} at ${g.home.shortName}",
                league = g.league,
                sport = g.sport,
                startMs = g.startMs,
                state = g.state,
                detail = g.detail,
                followed = team,
                networks = g.networks,
                imageUrl = g.imageUrl,
                options = emptyList(),
            )
        }, limit)
    }

    /**
     * Games from the viewer's own guides. [visible] is the caller's parental/library check, applied to
     * the channel before it can appear as a watch option — a followed team's game never bypasses it.
     */
    fun fromAirings(
        airings: List<SportsAiring>,
        followed: List<FollowedTeam>,
        nowMs: Long,
        limit: Int = 20,
        visible: (SportsAiring) -> Boolean = { true },
    ): List<MyTeamItem> {
        if (followed.isEmpty()) return emptyList()
        val matched = airings.mapNotNull { a ->
            if (a.kind != SportsKind.EVENT || a.endMs <= nowMs || !visible(a)) return@mapNotNull null
            FollowedTeams.match(followed, a)?.let { a to it }
        }
        // One card per matchup: every channel of this viewer's own guides that carries it is a watch option.
        return sort(matched.groupBy { (a, _) -> a.title.lowercase().replace(Regex("""[^a-z0-9]"""), "") }.values.mapNotNull { group ->
            val (a, team) = group.minByOrNull { it.first.startMs } ?: return@mapNotNull null
            MyTeamItem(
                id = "air-${a.channel.sourceId.value}|${a.channel.remoteId.value}|${a.startMs}",
                title = a.teams?.let { (x, y) -> "$x vs $y" } ?: a.title,
                league = a.league.orEmpty(),
                sport = a.sport,
                startMs = a.startMs,
                state = if (a.isLive(nowMs)) GameState.LIVE else GameState.PRE,
                detail = "",
                followed = team,
                networks = group.map { it.first.channelName }.distinct(),
                imageUrl = a.imageUrl,
                options = group.map { MyTeamOption(it.first.channel, it.first.categoryId, it.first.channelName, it.first.logoUrl) }.distinctBy { it.key },
            )
        }, limit)
    }

    /** Live games first, then the next kick-offs, then anything already finished. */
    fun sort(items: List<MyTeamItem>, limit: Int = 20): List<MyTeamItem> = items
        .sortedWith(compareBy({ if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }, { it.startMs }))
        .distinctBy { it.id }
        .take(limit)
}

/**
 * The "My teams" row's data: the active profile's followed teams against this viewer's own guides.
 * Guide-only on purpose — it needs no internet, and every card it produces is a channel the viewer
 * can actually tune to.
 */
class MyTeamsRepository(
    private val epg: EpgRepository,
    private val clock: Clock,
    private val follows: FollowedTeamsStore,
    private val limit: Int = 20,
) {
    suspend fun items(visible: (SportsAiring) -> Boolean = { true }): List<MyTeamItem> {
        val followed = follows.current()
        if (followed.isEmpty()) return emptyList()
        val now = clock.nowMs()
        val airings = runCatching { epg.sportsSchedule(now) }.getOrDefault(emptyList())
        return MyTeams.fromAirings(airings, followed, now, limit, visible)
    }
}
