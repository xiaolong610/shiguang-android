package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class WorkIdentityTest {
    private val e=Entry("one","a","星海 第1季",Kind.VIDEO,"https://a/1",category=ContentCategory.ANIME)
    @Test fun normalizedTitlesGroupButSeasonsYearsAndCategoriesStaySeparate(){
        val other=e.copy(id="two",sourceId="b",title="星海　第一季")
        assertFalse(WorkIdentity.same(e,other)) // Conservative: no inferred equivalence.
        assertTrue(WorkIdentity.same(e,e.copy(sourceId="b",title="星海 第１季【1080p】")))
        assertFalse(WorkIdentity.same(e,e.copy(title="星海 第2季")))
        assertFalse(WorkIdentity.same(e,e.copy(category=ContentCategory.VIDEO)))
        assertFalse(WorkIdentity.same(e.copy(title="星海2020"),e.copy(title="星海2024")))
    }
    @Test fun duplicateEditionsWithinOneSiteRequireManualSelection(){
        assertEquals(1,WorkIdentity.groups(listOf(e,e.copy(sourceId="b"))).size)
        assertEquals(3,WorkIdentity.groups(listOf(e,e.copy(id="edition"),e.copy(sourceId="b"))).size)
    }
    @Test fun episodesMatchNumberFormattingButAmbiguityAndNovelSubtitleMismatchNeedUserChoice(){
        val chapters=listOf(Chapter("第十二集","a"),Chapter("第13集","b"))
        assertEquals(0,ChapterIdentity.match("第012集",chapters,Kind.VIDEO))
        assertNull(ChapterIdentity.match("第12集",chapters+Chapter("EP12","c"),Kind.VIDEO))
        assertNull(ChapterIdentity.match("第12章 相遇",listOf(Chapter("第12章 分别","a")),Kind.NOVEL))
        assertNull(ChapterIdentity.match("特别篇",chapters,Kind.VIDEO))
        assertNull(ChapterIdentity.match("第12集下",listOf(Chapter("第十二集上","a")),Kind.VIDEO))
        assertEquals(0,ChapterIdentity.match("第十二章 相遇",listOf(Chapter("第十二章　相遇","a")),Kind.NOVEL))
    }
}
