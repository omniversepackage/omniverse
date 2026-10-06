package com.yodesla.omniverse.core.data.sports

/** A recognised pro team: league, sport and its primary colour (ARGB) for badges. */
data class Team(val nickname: String, val league: String, val sport: Sport, val color: Long)

/**
 * Pro team nicknames (NFL, NBA, MLB, NHL, plus big soccer clubs). Matching is by nickname at the
 * end of a name ("Cleveland Browns", "Browns", "CLE Browns"), case-insensitive. Facts only; no logos.
 */
object Teams {
    private fun nfl(n: String, c: Long) = Team(n, "NFL", Sport.FOOTBALL, c)
    private fun nba(n: String, c: Long = 0xFFC9082A) = Team(n, "NBA", Sport.BASKETBALL, c)
    private fun mlb(n: String, c: Long = 0xFF002D72) = Team(n, "MLB", Sport.BASEBALL, c)
    private fun nhl(n: String, c: Long = 0xFF111111) = Team(n, "NHL", Sport.HOCKEY, c)
    private fun soc(n: String, league: String, c: Long) = Team(n, league, Sport.SOCCER, c)

    private val all: List<Team> = listOf(
        nfl("Cardinals", 0xFF97233F), nfl("Falcons", 0xFFA71930), nfl("Ravens", 0xFF241773), nfl("Bills", 0xFF00338D),
        nfl("Panthers", 0xFF0085CA), nfl("Bears", 0xFF0B162A), nfl("Bengals", 0xFFFB4F14), nfl("Browns", 0xFF311D00),
        nfl("Cowboys", 0xFF003594), nfl("Broncos", 0xFFFB4F14), nfl("Lions", 0xFF0076B6), nfl("Packers", 0xFF203731),
        nfl("Texans", 0xFF03202F), nfl("Colts", 0xFF002C5F), nfl("Jaguars", 0xFF006778), nfl("Chiefs", 0xFFE31837),
        nfl("Raiders", 0xFF000000), nfl("Chargers", 0xFF0080C6), nfl("Rams", 0xFF003594), nfl("Dolphins", 0xFF008E97),
        nfl("Vikings", 0xFF4F2683), nfl("Patriots", 0xFF002244), nfl("Saints", 0xFFD3BC8D), nfl("Giants", 0xFF0B2265),
        nfl("Jets", 0xFF125740), nfl("Eagles", 0xFF004C54), nfl("Steelers", 0xFFFFB612), nfl("49ers", 0xFFAA0000),
        nfl("Seahawks", 0xFF002244), nfl("Buccaneers", 0xFFD50A0A), nfl("Titans", 0xFF0C2340), nfl("Commanders", 0xFF5A1414),
        nba("Celtics", 0xFF007A33), nba("Nets", 0xFF000000), nba("Knicks", 0xFF006BB6), nba("76ers", 0xFF006BB6), nba("Raptors", 0xFFCE1141),
        nba("Bulls", 0xFFCE1141), nba("Cavaliers", 0xFF860038), nba("Pistons", 0xFFC8102E), nba("Pacers", 0xFF002D62), nba("Bucks", 0xFF00471B),
        nba("Hawks", 0xFFE03A3E), nba("Hornets", 0xFF1D1160), nba("Heat", 0xFF98002E), nba("Magic", 0xFF0077C0), nba("Wizards", 0xFF002B5C),
        nba("Nuggets", 0xFF0E2240), nba("Timberwolves", 0xFF0C2340), nba("Thunder", 0xFF007AC1), nba("Trail Blazers", 0xFFE03A3E), nba("Jazz", 0xFF002B5C),
        nba("Warriors", 0xFF1D428A), nba("Clippers", 0xFFC8102E), nba("Lakers", 0xFF552583), nba("Suns", 0xFF1D1160), nba("Kings", 0xFF5A2D81),
        nba("Mavericks", 0xFF00538C), nba("Rockets", 0xFFCE1141), nba("Grizzlies", 0xFF5D76A9), nba("Pelicans", 0xFF0C2340), nba("Spurs", 0xFFC4CED4),
        mlb("Orioles", 0xFFDF4601), mlb("Red Sox", 0xFFBD3039), mlb("Yankees", 0xFF0C2340), mlb("Rays", 0xFF092C5C), mlb("Blue Jays", 0xFF134A8E),
        mlb("White Sox", 0xFF27251F), mlb("Guardians", 0xFF0C2340), mlb("Tigers", 0xFF0C2340), mlb("Royals", 0xFF004687), mlb("Twins", 0xFF002B5C),
        mlb("Astros", 0xFF002D62), mlb("Angels", 0xFFBA0021), mlb("Athletics", 0xFF003831), mlb("Mariners", 0xFF0C2C56), mlb("Rangers", 0xFF003278),
        mlb("Braves", 0xFFCE1141), mlb("Marlins", 0xFF00A3E0), mlb("Mets", 0xFF002D72), mlb("Phillies", 0xFFE81828), mlb("Nationals", 0xFFAB0003),
        mlb("Cubs", 0xFF0E3386), mlb("Reds", 0xFFC6011F), mlb("Brewers", 0xFF12284B), mlb("Pirates", 0xFF27251F), mlb("Cardinals MLB", 0xFFC41E3A),
        mlb("Diamondbacks", 0xFFA71930), mlb("Rockies", 0xFF33006F), mlb("Dodgers", 0xFF005A9C), mlb("Padres", 0xFF2F241D),
        nhl("Bruins", 0xFFFFB81C), nhl("Sabres", 0xFF003087), nhl("Red Wings", 0xFFCE1126), nhl("Panthers NHL", 0xFFC8102E), nhl("Canadiens", 0xFFAF1E2D),
        nhl("Senators", 0xFFC52032), nhl("Lightning", 0xFF002868), nhl("Maple Leafs", 0xFF00205B), nhl("Hurricanes", 0xFFCC0000), nhl("Blue Jackets", 0xFF002654),
        nhl("Devils", 0xFFCE1126), nhl("Islanders", 0xFF00539B), nhl("Flyers", 0xFFF74902), nhl("Penguins", 0xFFFCB514), nhl("Capitals", 0xFF041E42),
        nhl("Blackhawks", 0xFFCF0A2C), nhl("Avalanche", 0xFF6F263D), nhl("Stars", 0xFF006847), nhl("Wild", 0xFF154734), nhl("Predators", 0xFFFFB81C),
        nhl("Blues", 0xFF002F87), nhl("Jets NHL", 0xFF041E42), nhl("Ducks", 0xFFF47A38), nhl("Flames", 0xFFC8102E), nhl("Oilers", 0xFF041E42),
        nhl("Golden Knights", 0xFFB4975A), nhl("Kraken", 0xFF001628), nhl("Canucks", 0xFF00205B), nhl("Sharks", 0xFF006D75), nhl("Utah Hockey Club", 0xFF6CACE4),
        soc("Arsenal", "Premier League", 0xFFEF0107), soc("Chelsea", "Premier League", 0xFF034694), soc("Liverpool", "Premier League", 0xFFC8102E),
        soc("Manchester United", "Premier League", 0xFFDA291C), soc("Manchester City", "Premier League", 0xFF6CABDD), soc("Tottenham", "Premier League", 0xFF132257),
        soc("Newcastle", "Premier League", 0xFF241F20), soc("Aston Villa", "Premier League", 0xFF670E36), soc("Real Madrid", "La Liga", 0xFFFEBE10),
        soc("Barcelona", "La Liga", 0xFFA50044), soc("Atletico Madrid", "La Liga", 0xFFCB3524), soc("Bayern", "Bundesliga", 0xFFDC052D),
        soc("Dortmund", "Bundesliga", 0xFFFDE100), soc("Juventus", "Serie A", 0xFF000000), soc("Inter", "Serie A", 0xFF010E80), soc("AC Milan", "Serie A", 0xFFFB090B),
        soc("PSG", "Ligue 1", 0xFF004170), soc("Inter Miami", "MLS", 0xFFF7B5CD), soc("LA Galaxy", "MLS", 0xFF00245D),
    )

    // Disambiguation suffixes ("Cardinals MLB") exist only so the table can hold both; match on the bare nickname.
    private val byNick: Map<String, List<Team>> = all.groupBy { it.nickname.removeSuffix(" MLB").removeSuffix(" NHL").lowercase() }

    /**
     * The team a display name refers to ("Cleveland Browns", "Browns (CLE)"), or null. Ambiguous
     * nicknames shared across leagues (Cardinals, Panthers, Jets, Rangers, Kings, Giants…) prefer
     * [hint]'s league when given.
     */
    fun find(name: String, hint: String? = null): Team? {
        val cleaned = name.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), " ").replace(Regex("""[^a-z0-9 ]"""), " ").trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isEmpty()) return null
        for (len in 3 downTo 1) {
            val words = cleaned.split(' ')
            if (words.size < len) continue
            for (start in 0..words.size - len) {
                val nick = words.subList(start, start + len).joinToString(" ")
                val hits = byNick[nick] ?: continue
                return hits.firstOrNull { hint != null && it.league.equals(hint, ignoreCase = true) } ?: hits.first()
            }
        }
        return null
    }
}
