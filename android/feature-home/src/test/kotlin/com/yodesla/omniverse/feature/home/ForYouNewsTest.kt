package com.yodesla.omniverse.feature.home

import kotlin.test.*

class ForYouNewsTest {
    @Test fun ranksRelevantHeadlinesWithoutInventingMatches() {
        val related=InterestStory("Batman returns to cinemas","https://www.theguardian.com/film/test","New production announced","")
        val unrelated=related.copy(title="This week’s theatre guide",summary="A stage production")
        assertTrue(ForYouNews.relevance(related,listOf("Batman"))>ForYouNews.relevance(unrelated,listOf("Batman")))
        assertEquals(0,ForYouNews.relevance(unrelated,emptyList()))
        assertEquals(0,ForYouNews.relevance(unrelated,listOf("The Movie Season Episode")))
    }
}
