package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class SearchPlannerTest {
    private fun source(n:Int)=Source("id$n","站点$n",Kind.VIDEO,"maccms","https://s$n.example/api")
    @Test fun enablingAllSourcesDoesNotMakeTheInitialScreenQueryAll263(){
        val sources=(0 until 263).map{source(it)}
        assertEquals(6,SearchPlanner.batch(sources,"").size)
        assertEquals(8,SearchPlanner.batch(sources,"海").size)
        assertEquals(12,SearchPlanner.batch(sources,"星海").size)
        assertEquals(24,SearchPlanner.batch(sources,"星海",more=true).size)
        assertTrue(sources.all{it.enabled})
    }
    @Test fun healthyFastSourcesComeFirstAndRepeatedFailuresAreDeferredWithoutDisabling(){
        val a=source(1);val b=source(2);val c=source(3);val now=100000L
        val health=mapOf(a.id to SourceHealth(a.id,20,2,now,SearchPlanner.fingerprint(a)),b.id to SourceHealth(b.id,80,0,now,SearchPlanner.fingerprint(b)))
        assertEquals(listOf(b,c,a),SearchPlanner.order(listOf(a,b,c),health,now))
        assertTrue(a.enabled)
        assertEquals(a,SearchPlanner.order(listOf(a,c),health,now+16*60*1000).first())
    }
    @Test fun changedRuleDoesNotInheritOldFailurePenaltyAndDynamicSitesHaveSeparateQuota(){
        val original=source(1);val changed=original.copy(url="https://new.example/api")
        val health=mapOf(original.id to SourceHealth(original.id,10,5,100,SearchPlanner.fingerprint(original)))
        assertEquals(changed,SearchPlanner.order(listOf(changed),health,100).single())
        val dynamic=(1..20).map{source(it).copy(engine="ficket",rules="""{"original":{"method":"nightmare"}}""")}
        assertEquals(2,SearchPlanner.batch(dynamic,"星海").size)
        assertEquals(1,SearchPlanner.batch(dynamic,"").size)
    }
}
