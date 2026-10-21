package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class ReadingPaginationTest{
    @Test fun reverseDisplayPreservesRealChapterIndices(){assertEquals(listOf(3,2,1,0),chapterDisplayIndices(4,true));assertEquals(listOf(0,1,2,3),chapterDisplayIndices(4,false));assertEquals(emptyList<Int>(),chapterDisplayIndices(0,true))}
    @Test fun pagesCoverEveryCharacterOnceWithoutLosingFinalLine(){
        val text="甲乙丙丁戊己庚辛壬癸";val pages=measuredPageRanges(text.length,listOf(2,4,6,8,10),listOf(0f,20f,40f,60f,80f),listOf(20f,40f,60f,80f,100f),45f)
        assertEquals(listOf(0..3,4..7,8..9),pages);assertEquals(text,pages.joinToString(""){text.substring(it)})
    }
    @Test fun tallLineStillMakesProgressAndEmptyTextIsSafe(){assertEquals(listOf(0..1,2..3),measuredPageRanges(4,listOf(2,4),listOf(0f,100f),listOf(100f,200f),40f));assertEquals(1,measuredPageRanges(0,emptyList(),emptyList(),emptyList(),50f).size)}
    @Test fun completeMeasuredEmojiLinesKeepSurrogatePairsAndTrailingNewline(){val text="甲😀\n乙🌙\n";val pages=measuredPageRanges(text.length,listOf(4,8,8),listOf(0f,20f,40f),listOf(20f,40f,60f),20f);assertEquals(text,pages.joinToString(""){text.substring(it)});pages.forEach{assertFalse(Character.isLowSurrogate(text[it.first]))}}
    @Test fun invalidStoredModesFallBackToOriginalScrolling(){assertEquals(NovelTurnMode.SCROLL,NovelTurnMode.parse("old-mode"));assertEquals(ComicTurnMode.SCROLL,ComicTurnMode.parse(null))}
}
