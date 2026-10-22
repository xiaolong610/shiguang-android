package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class FollowUpdatesTest {
    private val entry=Entry("1","source","作品",Kind.NOVEL,"https://example.com/1")
    private fun detail(vararg titles:String)=Detail(entry,listOf(Line("目录",titles.map{Chapter(it,"https://example.com/${it.hashCode()}")})))
    @Test fun initialCheckEstablishesBaselineAndOnlyLaterAdditionsAreNew(){
        val first=FollowState.observe(null,detail("第一章","第二章"),0,1)
        assertEquals(0,first.newCount(null))
        val next=FollowState.observe(first,detail("第一章","第二章","第三章"),0,2)
        assertEquals(1,next.newCount(null));assertEquals(1,FollowState.observe(next,detail("第一章","第二章","第三章"),0,3).newCount(null))
        val read=Progress(entry,0,2,0,4,"第三章")
        assertEquals(0,next.newCount(read));assertEquals(0,next.unread(read))
    }
    @Test fun reorderingAndRemovalDoNotCreateNewChapters(){
        val first=FollowState.observe(null,detail("第一章","第二章","第三章"),0,1)
        val next=FollowState.observe(first,detail("第三章","第一章"),0,2)
        assertTrue(next.added.isEmpty())
    }
    @Test fun switchedLineStartsFreshAndFailureRoundTripKeepsLastGoodCatalog(){
        val old=FollowState.observe(null,detail("第一章"),0,1)
        val other=Detail(entry,listOf(Line("其他目录",listOf(Chapter("第二章","url")))))
        assertTrue(FollowState.observe(old,other,0,2).added.isEmpty())
        val failure=old.copy(error="超时",checked=4)
        val restored=FollowState.parse(failure.json())
        assertEquals(old.titles,restored.titles);assertEquals(1,restored.succeeded);assertEquals("超时",restored.error)
    }
}
