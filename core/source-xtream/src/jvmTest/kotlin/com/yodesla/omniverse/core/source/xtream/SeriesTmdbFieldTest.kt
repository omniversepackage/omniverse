package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.model.SourceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Panels name the series TMDB field "tmdb" (most) or "tmdb_id"; online skip lookup needs it. */
class SeriesTmdbFieldTest {
    private val src = SourceId("s")
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun seriesListReadsEitherFieldName() {
        assertEquals("1429", XtreamMappers.series(obj("""{"series_id":"7","name":"Attack on Titan","tmdb":"1429"}"""), src, 0)?.tmdbId)
        assertEquals("1429", XtreamMappers.series(obj("""{"series_id":"7","name":"Attack on Titan","tmdb_id":"1429"}"""), src, 0)?.tmdbId)
        assertNull(XtreamMappers.series(obj("""{"series_id":"7","name":"X","tmdb":"0"}"""), src, 0)?.tmdbId)
        assertNull(XtreamMappers.series(obj("""{"series_id":"7","name":"X","tmdb":" "}"""), src, 0)?.tmdbId)
    }

    @Test fun seriesInfoReadsTmdbWhenTheListHadNone() {
        val fallback = XtreamMappers.series(obj("""{"series_id":"7","name":"Attack on Titan"}"""), src, 0)!!
        val detail = XtreamMappers.seriesDetail(obj("""{"info":{"name":"Attack on Titan","tmdb":"1429"},"episodes":{}}"""), fallback)
        assertEquals("1429", detail.record.tmdbId)
    }
}
