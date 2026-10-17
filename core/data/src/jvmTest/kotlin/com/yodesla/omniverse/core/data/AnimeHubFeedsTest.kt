package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.data.metadata.AnimeHubFeeds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AnimeHubFeedsTest {
    @Test fun channelCoreNames() {
        assertEquals("Naruto", AnimeHubFeeds.channelCoreName("Amazon US: Naruto"))
        assertEquals("Anime", AnimeHubFeeds.channelCoreName("US: Pluto TV Anime"))
        assertEquals("One Piece", AnimeHubFeeds.channelCoreName("US| One Piece FHD"))
        assertEquals("Anime 24/7".replace(" 24/7", ""), AnimeHubFeeds.channelCoreName("Plex: Anime 24/7"))
        assertEquals("pokemon", AnimeHubFeeds.normalize("Pokémon"))
    }

    @Test fun onlyTmdbAnimationGenreSurvivesInChartOrder() {
        val page = Json.parseToJsonElement(
            """{"results":[
              {"id":1,"genre_ids":[18],"first_air_date":"2020-01-01","original_language":"en"},
              {"id":2,"genre_ids":[16,10759],"first_air_date":"2024-04-01","original_language":"ja"},
              {"id":3,"genre_ids":[16],"first_air_date":"2023-01-01","original_language":"en"}]}"""
        ).jsonObject
        val out = AnimeHubFeeds.animated(page, "tv")
        assertEquals(listOf("2", "3"), out.map { it.id })
        assertEquals(listOf("ja", "en"), out.map { it.lang })
    }
}
