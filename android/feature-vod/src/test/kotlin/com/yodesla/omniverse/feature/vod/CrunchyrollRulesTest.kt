package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.designsystem.CategoryBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 86b: the pure rules behind the Crunchyroll layout. */
class CrunchyrollRulesTest {
    private fun row(
        id: String,
        name: String = "Title $id",
        rating: Float? = null,
        year: Int? = 2015,
        art: String? = "https://art/$id",
        kind: ContentKind = ContentKind.SERIES,
        backdrop: String? = null,
        card: String? = null,
        tmdb: String? = null,
    ) = PosterRow(
        ContentKey(SourceId("cr"), kind, RemoteId(id)), name, art, year, rating, RemoteId("crunchyroll"),
        backdropUrl = backdrop, cardBackdropUrl = card, tmdbId = tmdb,
    )

    // ---- 87b. card art is never the hero art ----------------------------------------

    @Test fun cardsUseTheSecondBackdropWhenTheTitleHasOne() {
        val r = row("a", backdrop = "https://art/banner", card = "https://art/card")
        assertEquals(CrunchArt.Card("https://art/card"), crunchyrollCardArt(r))
        assertFalse(crunchyrollCardArt(r).needsTitleOverlay())
    }

    @Test fun cardsReusingTheHeroBackdropAreFlaggedForATitleTreatment() {
        val r = row("a", backdrop = "https://art/banner")
        assertEquals(CrunchArt.Banner("https://art/banner"), crunchyrollCardArt(r))
        assertTrue(crunchyrollCardArt(r).needsTitleOverlay())
    }

    @Test fun cardsFallBackToThePosterThenToNothing() {
        assertEquals(CrunchArt.Poster("https://art/b"), crunchyrollCardArt(row("b")))
        assertEquals(CrunchArt.None, crunchyrollCardArt(row("c", art = null)))
    }

    // ---- 5. "Sub | Dub" --------------------------------------------------------------

    @Test fun dubWordsGiveDubbed() {
        assertEquals("Dubbed", subDubLabel(listOf("One Piece (dub)")))
        assertEquals("Dubbed", subDubLabel(listOf("Naruto English Dub")))
        assertEquals("Dubbed", subDubLabel(listOf("Bocchi the Rock!", "Bocchi the Rock! Dubbed")))
    }

    @Test fun subWordsGiveSubtitled() {
        assertEquals("Subtitled", subDubLabel(listOf("Attack on Titan VOSTFR")))
        assertEquals("Subtitled", subDubLabel(listOf("Jujutsu Kaisen (sub)")))
        assertEquals("Subtitled", subDubLabel(listOf("Frieren — Japanese audio")))
        assertEquals("Subtitled", subDubLabel(listOf("Made in Abyss subbed")))
    }

    @Test fun bothAcrossTheMergedCopiesGiveSubDub() {
        assertEquals("Sub | Dub", subDubLabel(listOf("Naruto", "Naruto (sub)", "Naruto (dub)")))
        assertEquals("Sub | Dub", subDubLabel(listOf("Crunchyroll Sub", "One Piece (dub)")))
        // A copy with no audio marker of its own still only proves one side.
        assertEquals("Dubbed", subDubLabel(listOf("Naruto", "Naruto (dub)")))
    }

    @Test fun unknownAudioIsNeverGuessed() {
        assertNull(subDubLabel(listOf("Chainsaw Man")))
        assertNull(subDubLabel(emptyList()))
        // Whole words only: a title that merely contains the letters is not an audio label.
        assertNull(subDubLabel(listOf("Substitute Hero")))
        assertNull(subDubLabel(listOf("Dubliners")))
        assertNull(subDubLabel(listOf("Resubmission")))
    }

    // ---- 2. Hero carousel picking ----------------------------------------------------

    @Test fun heroTakesFiveBestRatedTitlesWithArt() {
        val pool = (1..12).map { row("$it", rating = it.toFloat()) }
        val featured = crunchyrollFeatured(pool)
        assertEquals(5, featured.size)
        assertEquals(listOf(12f, 11f, 10f, 9f, 8f), featured.map { it.rating })
    }

    @Test fun heroNeverRepeatsATitle() {
        val a = row("a", rating = 9f)
        val featured = crunchyrollFeatured(listOf(a, a, row("b", rating = 8f)))
        assertEquals(listOf("a", "b"), featured.map { it.key.remoteId.value })
    }

    @Test fun heroNeedsArt() {
        val pool = listOf(row("a", rating = 9f, art = null), row("b", rating = 8f, art = ""), row("c", rating = 7f))
        assertEquals(listOf("c"), crunchyrollFeatured(pool).map { it.key.remoteId.value })
    }

    @Test fun hiddenTitlesNeverReachTheBillboard() {
        val pool = (1..6).map { row("$it", rating = it.toFloat()) }
        val hidden = pool.map { it.key.remoteId.value }.contains("6")
        val featured = crunchyrollFeatured(pool, visible = { it.key.remoteId.value != "6" })
        assertTrue(hidden)
        assertEquals(listOf("5", "4", "3", "2", "1"), featured.map { it.key.remoteId.value })
    }

    @Test fun aSmallLibraryRotatesThroughFewerTitles() {
        assertEquals(2, crunchyrollFeatured(listOf(row("a", rating = 9f), row("b", rating = 5f))).size)
        assertTrue(crunchyrollFeatured(emptyList()).isEmpty())
    }

    // ---- 3. Row order ---------------------------------------------------------------

    private val plan = plan(CategoryBrand.CRUNCHYROLL, "Shows")
    private val bigPool = (1..6).map { row("$it", rating = 9f, year = 2015) } +
        (7..10).map { row("$it", year = 1998) } +
        (11..12).map { row("$it", year = 2015) }

    @Test fun continueWatchingComesFirstAsLandscapeCards() {
        val cw = listOf(ContinuePosterUi(row("cw1"), progress = 0.4f, episode = true))
        val rows = crunchyrollRows(bigPool, cw, plan)
        assertEquals("cw", rows.first().key)
        assertTrue(rows.first().landscape)
        assertEquals(listOf("cw", "top", "rated", "fresh", "vault", "more-0"), rows.map { it.key })
        assertFalse(rows.drop(1).any { it.landscape })
    }

    @Test fun noContinueWatchingNoRow() {
        assertEquals(listOf("top", "rated", "fresh", "vault", "more-0"), crunchyrollRows(bigPool, emptyList(), plan).map { it.key })
    }

    @Test fun planRowsUseTheCrunchyrollVoiceAndOrder() {
        val rows = crunchyrollRows(bigPool, emptyList(), plan)
        assertEquals(
            listOf("Top picks for you", "Most popular", "Newly added", "Classics", "Browse all"),
            rows.map { it.title },
        )
    }

    @Test fun aShelfNeedsFourTitlesToShow() {
        val small = (1..3).map { row("$it", rating = 9f) }
        assertEquals(listOf("top"), crunchyrollRows(small, emptyList(), plan).map { it.key })
    }

    @Test fun browseAllFollowsThePlanRows() {
        val pool = (1..45).map { row("$it") }
        val rows = crunchyrollRows(pool, emptyList(), plan)
        assertEquals(listOf("top", "fresh", "more-0", "more-1"), rows.map { it.key })
        assertEquals(listOf("Top picks for you", "Newly added", "Browse all", "Browse all · 2"), rows.map { it.title })
        assertEquals(35, rows.filter { it.key.startsWith("more-") }.sumOf { it.items.size })
    }

    // ---- 3. Continue Watching labels -------------------------------------------------

    @Test fun minutesLeftRoundsUpAndStaysSilentWhenUnknown() {
        assertEquals("18m left", crunchyrollMinutesLeft(20L * 60_000L, 38L * 60_000L))
        assertEquals("1m left", crunchyrollMinutesLeft(0L, 31_000L))
        assertNull(crunchyrollMinutesLeft(0L, null))
        assertNull(crunchyrollMinutesLeft(40L * 60_000L, 38L * 60_000L))
    }

    @Test fun episodeLabelNeedsBothNumbers() {
        assertEquals("S1 E5", crunchyrollEpisodeLabel(1, 5))
        assertNull(crunchyrollEpisodeLabel(null, 5))
        assertNull(crunchyrollEpisodeLabel(2, null))
    }

    // ---- 5. Age rating / genres ------------------------------------------------------

    @Test fun ageRatingOnlyFromANameThatCarriesOne() {
        assertEquals("TV-MA", ageRatingLabel("TV-MA", "Crunchyroll"))
        assertEquals("PG-13", ageRatingLabel("One Piece: Stampede (PG-13)"))
        assertNull(ageRatingLabel("Chainsaw Man"))
        assertNull(ageRatingLabel("tv-ma")) // ratings are upper-case in provider names
    }

    @Test fun genresAreCappedAtThree() {
        assertEquals(listOf("Action", "Adventure", "Comedy"), crunchyrollGenres("Action|Adventure|Comedy|Fantasy"))
        assertEquals(listOf("Action"), crunchyrollGenres("Action"))
        assertTrue(crunchyrollGenres(null).isEmpty())
    }

    // ---- 84l. Continue Watching belongs to the category ---------------------

    private val crSel: Pair<SourceId?, String?> = SourceId("cr") to "crunchyroll"

    private fun cwCard(
        src: String,
        id: String,
        name: String = "Title $id",
        cat: String? = "crunchyroll",
        tmdb: String? = null,
    ) = ContinuePosterUi(
        PosterRow(
            ContentKey(SourceId(src), ContentKind.SERIES, RemoteId(id)), name, "https://art/$id",
            2015, 8f, cat?.let(::RemoteId), tmdbId = tmdb,
        ),
        progress = 0.5f, episode = false,
    )

    @Test fun cwKeepsCardsFromTheSelectedCategory() {
        val cw = listOf(cwCard("cr", "a"))
        assertEquals(listOf("a"), cwForBrowseCategory(cw, listOf(row("a")), crSel).map { it.poster.key.remoteId.value })
    }

    @Test fun cwDropsTitlesThatAreNotOnThisService() {
        // Kory's TV complaint: Midsommar / The Founder are other providers' movies, never Crunchyroll's.
        val cw = listOf(cwCard("cr", "a"), cwCard("plex", "midsommar", cat = "movies"), cwCard("plex", "founder", cat = "movies"))
        assertEquals(listOf("a"), cwForBrowseCategory(cw, listOf(row("a")), crSel).map { it.poster.key.remoteId.value })
    }

    @Test fun cwKeepsTheSameTitleWatchedThroughAnotherSource() {
        // Exact TMDB id against the category pool: the same show, watched through Plex.
        val pool = listOf(row("a", tmdb = "tt123"))
        val cw = listOf(cwCard("plex", "p1", cat = "other", tmdb = "tt123"))
        assertEquals(1, cwForBrowseCategory(cw, pool, crSel).size)
    }

    @Test fun cwKeepsAPoolTitleEvenWhenThePosterCarriesNoCategory() {
        val pool = listOf(row("a"))
        val cw = listOf(cwCard("cr", "a", cat = null))
        assertEquals(1, cwForBrowseCategory(cw, pool, crSel).size)
    }

    @Test fun cwNeverMatchesOnTitleAlone() {
        val pool = listOf(row("a", name = "Midsommar", tmdb = "tt60"))
        val cw = listOf(cwCard("plex", "x", name = "Midsommar", cat = "movies"))
        assertTrue(cwForBrowseCategory(cw, pool, crSel).isEmpty())
    }

    @Test fun cwWithoutASelectionKeepsEverything() {
        assertEquals(1, cwForBrowseCategory(listOf(cwCard("plex", "m", cat = "movies")), emptyList(), null).size)
    }

    // ---- 84l. Hero carousel paging ------------------------------------------

    @Test fun heroStepWrapsForwardAndBack() {
        assertEquals(1, crunchyrollHeroStep(0, 5, 1))
        assertEquals(0, crunchyrollHeroStep(4, 5, 1))
        assertEquals(4, crunchyrollHeroStep(0, 5, -1))
        assertEquals(3, crunchyrollHeroStep(4, 5, -1))
    }

    @Test fun heroStepOnASmallOrEmptyCarouselStaysAtZero() {
        assertEquals(0, crunchyrollHeroStep(0, 0, 1))
        assertEquals(0, crunchyrollHeroStep(0, 1, 1))
        assertEquals(0, crunchyrollHeroStep(0, 1, -1))
    }

    @Test fun focusedPosterControlsBackdropUntilHeroTakesFocus() {
        val hero = row("hero")
        val poster = row("poster")
        assertEquals(hero, crunchyrollBackdropRow(hero, null, heroFocused = false))
        assertEquals(poster, crunchyrollBackdropRow(hero, poster, heroFocused = false))
        assertEquals(hero, crunchyrollBackdropRow(hero, poster, heroFocused = true))
    }
}
