package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class NovelSearchTest {
    @Test fun hitsRetainChapterAndParagraphForNavigation(){
        val text="第一段没有目标。\n\n第二段有星海。\n\n第三段也有星海。"
        val hits=NovelSearch.hits(text,"星海",chapter=7)
        assertEquals(listOf(7,7),hits.map{it.chapter});assertEquals(listOf(1,2),hits.map{it.paragraph})
        hits.forEach{assertEquals("星海",text.substring(it.offset,it.offset+2))}
    }
    @Test fun emptyQueriesAndResultLimitsAreBounded(){
        assertTrue(NovelSearch.hits("星海","").isEmpty());assertEquals(3,NovelSearch.hits("星海".repeat(500),"星海",limit=3).size)
        assertEquals(2,NovelSearch.hits("ABC abc","abc").size)
    }
    @Test fun snippetsDoNotSplitEmojiSurrogatePairs(){
        val text="前".repeat(5)+"😀"+"甲".repeat(23)+"关键词"+"乙".repeat(35)+"😀"+"后".repeat(40)
        val snippet=NovelSearch.hits(text,"关键词").single().snippet
        assertFalse(Character.isLowSurrogate(snippet.first()));assertFalse(Character.isHighSurrogate(snippet.last()))
        assertTrue(snippet.contains("关键词"))
    }
}
