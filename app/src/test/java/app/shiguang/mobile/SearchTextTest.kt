package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {
    private fun entry(title:String)=Entry(title,"source",title,Kind.NOVEL,"https://example.com/$title")
    @Test fun oneCharacterFragmentsAndMissingMiddleCharactersMatch(){
        assertTrue(SearchText.score("星海远航","海")>0)
        assertTrue(SearchText.score("星海远航","星海")>0)
        assertTrue(SearchText.score("星海远航","星远航")>0)
        assertEquals(0,SearchText.score("星海远航","雨"))
    }
    @Test fun fullWidthCasePunctuationAndSeparateWordsAreNormalized(){
        assertTrue(SearchText.score("ＡＢＣ：星海·第二季","abc 星海")>0)
        assertTrue(SearchText.score("星海远航 第二季","星海 第二季")>0)
    }
    @Test fun boundedTypoToleranceDoesNotTurnShortQueriesIntoUnrelatedMatches(){
        assertTrue(SearchText.score("星海远航","星海远杭")>0)
        assertEquals(0,SearchText.score("星海远航","山川物语"))
        assertEquals(0,SearchText.score("星海","心河"))
        assertEquals(0,SearchText.score("山川物语","星"))
    }
    @Test fun exactTitleRanksBeforeFragmentsAndSuggestionsOnlyUseKnownTitles(){
        val entries=listOf(entry("星海远航"),entry("星海"),entry("远方的星海"))
        assertEquals("星海",SearchText.rank(entries,"星海").first().title)
        assertEquals(listOf("星海远航"),SearchText.suggestions(entries,"星远航"))
        assertTrue(SearchText.suggestions(entries,"未知作品").isEmpty())
        assertTrue(SearchText.broader("星").isEmpty());assertTrue(SearchText.broader("星海").isEmpty())
        assertEquals(listOf("星海","远航"),SearchText.broader("星海远航"))
    }
}
