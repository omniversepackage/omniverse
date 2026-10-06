package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

private val iptv = SourceId("iptv")
private val plex = SourceId("plex")

private fun key(source: SourceId, kind: ContentKind, id: String) = ContentKey(source, kind, RemoteId(id))

private fun poster(
    k: ContentKey,
    name: String,
    categoryId: String? = "cat",
    tmdbId: String? = null,
    year: Int? = null,
): PosterRow = PosterRow(k, name, "https://art/${k.sourceId.value}-${k.remoteId.value}.jpg", year, null, categoryId?.let(::RemoteId), tmdbId)

private fun prog(k: ContentKey, parentId: String? = null, updatedMs: Long = 0L) =
    Progress(k, parentId?.let(::RemoteId), 1_000L, 2_000L, updatedMs)

private val allowAll: Visibility = { _, _, _ -> true }

private fun lookup(vararg posters: PosterRow): suspend (ContentKey) -> PosterRow? {
    val byKey = posters.associateBy { it.key }
    return { key -> byKey[key] }
}

class ScreensaverArtTest {
    @Test fun parentalLockedAndSwitchedOffCategoriesAreNeverPicked() = runBlocking {
        val lockedMovie = key(iptv, ContentKind.VOD, "m-locked")
        val lockedShow = key(iptv, ContentKind.SERIES, "s-locked")
        val openMovie = key(iptv, ContentKind.VOD, "m-open")
        val noCategory = key(plex, ContentKind.VOD, "m-plain")
        // Home's rule: a poster with no category is not filtered by the category check.
        val posters = listOf(
            poster(lockedMovie, "Locked movie"),
            poster(lockedShow, "Locked show"),
            poster(openMovie, "Open movie"),
            poster(noCategory, "Plain movie", categoryId = null),
        )
        val blockKids: Visibility = { _, sourceId, category -> !(sourceId == "iptv" && category == "cat") }

        val picked = pickScreensaverArt(
            continueWatching = listOf(prog(lockedMovie)),
            recentlyAddedMovies = listOf(poster(openMovie, "Open movie"), poster(noCategory, "Plain movie", categoryId = null)),
            recentlyAddedShows = listOf(poster(lockedShow, "Locked show")),
            visibility = blockKids,
            posterOf = lookup(*posters.toTypedArray()),
        )

        assertEquals(listOf("Plain movie"), picked.map { it.title })
    }

    @Test fun rotationStartsWithContinueWatchingThenAlternatesMoviesAndShows() = runBlocking {
        val cwOld = key(iptv, ContentKind.VOD, "cw-old")
        val cwNew = key(iptv, ContentKind.VOD, "cw-new")
        val movies = listOf(
            poster(key(iptv, ContentKind.VOD, "m1"), "Movie 1"),
            poster(key(iptv, ContentKind.VOD, "m2"), "Movie 2"),
            poster(key(iptv, ContentKind.VOD, "m3"), "Movie 3"),
        )
        val shows = listOf(
            poster(key(iptv, ContentKind.SERIES, "s1"), "Show 1"),
            poster(key(iptv, ContentKind.SERIES, "s2"), "Show 2"),
        )
        val posters = listOf(poster(cwNew, "Watched new"), poster(cwOld, "Watched old")) + movies + shows

        val picked = pickScreensaverArt(
            continueWatching = listOf(prog(cwOld, updatedMs = 100L), prog(cwNew, updatedMs = 300L)),
            recentlyAddedMovies = movies,
            recentlyAddedShows = shows,
            visibility = allowAll,
            posterOf = lookup(*posters.toTypedArray()),
        )

        assertEquals(
            listOf("Watched new", "Watched old", "Movie 1", "Show 1", "Movie 2", "Show 2", "Movie 3"),
            picked.map { it.title },
        )
    }

    @Test fun sameTitleFromSeveralSourcesTakesOneSlotAndTheFirstSeenWins() = runBlocking {
        val fromIptv = key(iptv, ContentKind.VOD, "1")
        val fromPlex = key(plex, ContentKind.VOD, "2")
        val picked = pickScreensaverArt(
            continueWatching = listOf(prog(fromIptv, updatedMs = 10L)),
            recentlyAddedMovies = listOf(poster(fromPlex, "Same movie (plex)", tmdbId = "tt9")),
            recentlyAddedShows = listOf(poster(key(plex, ContentKind.SERIES, "3"), "Same movie (plex show)", tmdbId = "tt9")),
            visibility = allowAll,
            posterOf = lookup(poster(fromIptv, "Same movie (iptv)", tmdbId = "tt9")),
        )

        // VOD and SERIES are different groups, so the show keeps its own slot; the two movie copies collapse.
        assertEquals(listOf("Same movie (iptv)", "Same movie (plex show)"), picked.map { it.title })
        assertEquals(fromIptv, picked.first().key)
    }

    @Test fun episodeProgressUsesItsSeriesPosterAndMissingPostersAreSkipped() = runBlocking {
        val series = key(iptv, ContentKind.SERIES, "show")
        val episode = key(iptv, ContentKind.EPISODE, "ep1")
        val gone = key(iptv, ContentKind.VOD, "gone")
        val picked = pickScreensaverArt(
            continueWatching = listOf(prog(episode, parentId = "show", updatedMs = 5L), prog(gone)),
            recentlyAddedMovies = emptyList(),
            recentlyAddedShows = emptyList(),
            visibility = allowAll,
            posterOf = lookup(poster(series, "My show")),
        )

        assertEquals(listOf("My show"), picked.map { it.title })
        assertEquals(series, picked.first().key)
    }

    @Test fun slotCarriesTheArtworkUrlTheCatalogAlreadyHolds() = runBlocking {
        val movie = key(iptv, ContentKind.VOD, "m1")
        val picked = pickScreensaverArt(
            continueWatching = emptyList(),
            recentlyAddedMovies = listOf(poster(movie, "Dune (1984)", year = 1984)),
            recentlyAddedShows = emptyList(),
            visibility = allowAll,
            posterOf = lookup(),
        )

        assertEquals("Dune", picked.single().title)
        assertEquals("https://art/iptv-m1.jpg", picked.single().image)
    }
}
