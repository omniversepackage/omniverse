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

    @Test fun summariesReadAsCleanSentences() {
        val html="<p>The film-maker enjoyed a long career, from horror to Cannibal and Breaking Bad</p> <p>Peter Medak, the director, has died aged 88 &amp; more.</p> <a href=\"https://www.theguardian.com/x\">Continue reading...</a>"
        assertEquals("The film-maker enjoyed a long career, from horror to Cannibal and Breaking Bad. Peter Medak, the director, has died aged 88 & more.", ForYouNews.cleanSummary(html))
        val long="<p>" + "One short sentence here. ".repeat(30) + "</p>"
        val cut=ForYouNews.cleanSummary(long, max = 120)
        assertTrue(cut.length <= 120 && cut.endsWith("here."), "ends on a whole sentence: $cut")
        val words=ForYouNews.cleanSummary("<p>" + "word ".repeat(80) + "</p>", max = 60)
        assertTrue(words.endsWith("word…") && words.length <= 61, "ends on a whole word: $words")
    }

    @Test fun matchesWholeTitlesNotStrayWords() {
        val story=InterestStory("West Ham’s Asseyi: ‘When I was four I said I wanted to be like Zidane’","https://www.theguardian.com/sport/x","Football","")
        assertEquals(0,ForYouNews.relevance(story,listOf("When Good Ghouls Go Bad")), "one shared word is not a match")
        val anime=InterestStory("Mushoku Tensei season three gets a date","https://www.theguardian.com/tv/x","","")
        assertEquals(3,ForYouNews.relevance(anime,listOf("Mushoku Tensei: Jobless Reincarnation")), "the name before the subtitle counts")
        val team=InterestStory("Arsenal go top","https://www.theguardian.com/sport/y","","")
        assertTrue(ForYouNews.relevance(team,listOf("Arsenal"))>=3)
    }
}
