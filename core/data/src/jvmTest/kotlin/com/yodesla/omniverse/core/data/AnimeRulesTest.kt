package com.yodesla.omniverse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 92: the anime classifier behind the Crunchyroll "Anime library". */
class AnimeRulesTest {
    @Test fun categoryTokensMatch() {
        assertTrue(AnimeRules.isAnime("ANIME | SUB", null))
        assertTrue(AnimeRules.isAnime("Animes", null))
        assertTrue(AnimeRules.isAnime("Anime Dubbed", null))
        assertTrue(AnimeRules.isAnime("Crunchyroll (dub)", null))
        assertTrue(AnimeRules.isAnime("Funimation", null))
        assertTrue(AnimeRules.isAnime("Hidive", null))
        assertTrue(AnimeRules.isAnime("Manga", null))
        assertTrue(AnimeRules.isAnime("anime", null))
        assertTrue(AnimeRules.isAnime("Anime-Dubbed", null))
        assertTrue(AnimeRules.isAnime("ANIME: SUB", null))
    }

    @Test fun genreTokensMatch() {
        assertTrue(AnimeRules.isAnime("movies", "Action|Anime"))
        assertTrue(AnimeRules.isAnime("movies", "anime"))
        assertTrue(AnimeRules.isAnime("shows", "Animation, Anime, Action"))
    }

    @Test fun plainAnimationKidsCartoonsAreNotAnime() {
        assertFalse(AnimeRules.isAnime("movies", "Animation"))
        assertFalse(AnimeRules.isAnime("Kids & Cartoons", "Animation|Kids"))
        assertFalse(AnimeRules.isAnime("Cartoons", null))
        assertFalse(AnimeRules.isAnime("Animated Movies", "Animation"))
        assertFalse(AnimeRules.isAnime("SUB | DUB", null))
        assertFalse(AnimeRules.isAnime(null, null))
    }

    @Test fun plexAnimationRuleNeedsJapanData() {
        assertTrue(AnimeRules.isAnime("shows", "Animation", "Japanese"))
        assertTrue(AnimeRules.isAnime("shows", "Animation|Crime", "Japan"))
        assertTrue(AnimeRules.isAnime("shows", "Animation", "jp"))
        assertFalse(AnimeRules.isAnime("shows", "Animation", "US"))
        assertFalse(AnimeRules.isAnime("shows", "Animation", null))
        assertFalse(AnimeRules.isAnime("shows", "Kids", "Japanese"))
    }

    @Test fun tokensSplitOnEveryProviderSeparator() {
        assertEquals(listOf("anime", "sub"), AnimeRules.tokens("ANIME | SUB"))
        assertEquals(listOf("crunchyroll", "dub"), AnimeRules.tokens("Crunchyroll (dub)"))
        assertEquals(listOf("anime", "dubbed"), AnimeRules.tokens("Anime-Dubbed"))
    }
}
