package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val src = SourceId("s")
private val netflixPlan = BrandPlan("Top 10 Movies in this collection", "Critically acclaimed", "New releases", "Blockbusters from the vault", "Trending now", true, "MOVIES")

private fun row(
    i: Int, year: Int? = 2020, rating: Float? = null, backdrop: String? = null, plot: String? = null,
    poster: String? = "p$i", card: String? = null, logo: String? = null,
) = PosterRow(
    ContentKey(src, ContentKind.VOD, RemoteId("r$i")), "Title $i", poster, year, rating, RemoteId("c"),
    null, backdrop, plot, logoUrl = logo, cardBackdropUrl = card,
)

private fun cw(i: Int) = ContinuePosterUi(row(i), 0.5f, false)

class NetflixRowsTest {
    @Test
    fun rowOrderMatchesTheNativeApp() {
        val pool = (1..35).map { row(it, year = 2000, rating = 8f) }
        val rows = netflixRows(listOf(cw(1)), listOf(row(31)), pool, netflixPlan, "Kory")
        assertEquals(
            listOf("cw", "top10", "mylist", "rated", "fresh", "vault", "more-0", "more-1"),
            rows.map {
                when (it) {
                    is NetflixRow.ContinueWatching -> "cw"
                    is NetflixRow.Top10 -> "top10"
                    is NetflixRow.MyList -> "mylist"
                    is NetflixRow.Shelf -> it.key
                }
            },
        )
        assertEquals("Continue Watching for Kory", (rows.first() as NetflixRow.ContinueWatching).title)
        assertEquals("Top 10 Movies in this collection", (rows[1] as NetflixRow.Top10).title)
        assertEquals("My List", (rows[2] as NetflixRow.MyList).title)
        assertEquals("Critically acclaimed", (rows[3] as NetflixRow.Shelf).title)
        assertEquals("Browse all", (rows[6] as NetflixRow.Shelf).title)
        assertEquals("Browse all · 2", (rows[7] as NetflixRow.Shelf).title)
    }

    @Test
    fun emptyRowsAreDroppedEntirely() {
        val rows = netflixRows(emptyList(), emptyList(), emptyList(), netflixPlan, null)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun curatedRowsNeedFourMembers() {
        val pool = (1..12).map { row(it, year = 2015, rating = if (it <= 3) 9f else 5f) }
        val rows = netflixRows(emptyList(), emptyList(), pool, netflixPlan, null)
        // 3 highly rated (row dropped), 12 new (kept), none old (dropped), 2 left over for Browse all.
        assertEquals(listOf("top10", "fresh", "more-0"), rows.map { (it as? NetflixRow.Shelf)?.key ?: "top10" })
    }

    @Test
    fun continueWatchingTitleWithoutProfile() {
        val rows = netflixRows(listOf(cw(1)), emptyList(), emptyList(), netflixPlan, null)
        assertEquals("Continue Watching", (rows.single() as NetflixRow.ContinueWatching).title)
    }

    @Test
    fun topTenIsTheFirstTenOfTheCollection() {
        val pool = (1..15).map { row(it) }
        val rows = netflixRows(emptyList(), emptyList(), pool, netflixPlan, null)
        val top = assertIs<NetflixRow.Top10>(rows.first())
        assertEquals(10, top.rows.size)
        assertEquals("r1", top.rows.first().key.remoteId.value)
    }

    @Test
    fun cardArtPrefersCardBackdropThenBannerThenPosterThenBlank() {
        assertIs<NetflixArt.Backdrop>(netflixCardArt(row(1, card = "card")))
        assertIs<NetflixArt.BannerReuse>(netflixCardArt(row(1, backdrop = "wide")))
        assertIs<NetflixArt.PosterFill>(netflixCardArt(row(1, backdrop = "  ")))
        assertIs<NetflixArt.Blank>(netflixCardArt(row(1, poster = null, backdrop = null)))
    }

    // Task 87b: the billboard's picture and the card's picture are never the same one.
    @Test
    fun cardArtIsNeverTheBillboardArt() {
        val two = row(1, backdrop = "banner", card = "card")
        assertEquals("card", assertIs<NetflixArt.Backdrop>(netflixCardArt(two)).url)
        assertEquals("banner", assertIs<NetflixArt.Backdrop>(netflixBillboardArt(two)).url)
        // Only one backdrop exists: the card keeps it but is flagged as reused, so the title
        // treatment goes on top of it.
        assertEquals("banner", assertIs<NetflixArt.BannerReuse>(netflixCardArt(row(1, backdrop = "banner"))).url)
    }

    @Test
    fun onlyReusedOrFallbackArtNeedsATitleOverlay() {
        assertTrue(netflixCardArt(row(1, card = "card")).needsTitleOverlay().not())
        assertTrue(netflixCardArt(row(1, backdrop = "banner")).needsTitleOverlay())
        assertTrue(netflixCardArt(row(1)).needsTitleOverlay())
        assertTrue(netflixCardArt(row(1, poster = null, backdrop = null)).needsTitleOverlay())
    }

    @Test
    fun billboardCropsThePosterWhenNoBackdrop() {
        assertIs<NetflixArt.Backdrop>(netflixBillboardArt(row(1, backdrop = "wide")))
        assertIs<NetflixArt.Backdrop>(netflixBillboardArt(row(1)))
        assertIs<NetflixArt.Blank>(netflixBillboardArt(row(1, poster = null)))
    }

    @Test
    fun selectionFiltersCardsToTheCategory() {
        val p = row(1)
        assertTrue(inBrowseCategory(p, null))
        assertTrue(inBrowseCategory(p, null to null))
        assertTrue(inBrowseCategory(p, src to "c"))
        assertTrue(inBrowseCategory(p, null to "c"))
        assertTrue(inBrowseCategory(p, src to "")) // empty category = all categories of that source
        assertTrue(inBrowseCategory(p, SourceId("other") to null).not())
        assertTrue(inBrowseCategory(p, null to "other").not())
    }

    @Test
    fun kindLabelIsFilmOrSeries() {
        assertEquals("FILM", netflixKindLabel("Movies"))
        assertEquals("SERIES", netflixKindLabel("Shows"))
    }

    @Test
    fun qualityComesFromNameTagsOnly() {
        assertEquals("4K", qualityTag("Some Movie (4K)"))
        assertEquals("4K", qualityTag("Show S01 [UHD]"))
        assertEquals("HD", qualityTag("Movie [FHD]"))
        assertEquals("HD", qualityTag("Movie 1080p"))
        assertNull(qualityTag("Plain Title"))
    }

    @Test
    fun timeLeftCountsHoursAndMinutes() {
        assertEquals("1h 12m left", timeLeftLabel(4_320_000L))
        assertEquals("12m left", timeLeftLabel(720_000L))
        assertEquals("2h left", timeLeftLabel(7_200_000L))
        assertNull(timeLeftLabel(30_000L))
        assertNull(timeLeftLabel(null))
    }
}
