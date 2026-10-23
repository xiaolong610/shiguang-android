package app.shiguang.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SearchPerformance091Test {
    @Test fun controlledThirtySourceComparisonMeasuresFirstResultWithoutDroppingFinalResults()=runBlocking {
        val sources=(0 until 30).map{Source("s$it","站点$it",Kind.VIDEO,"maccms","https://example.com/$it")}
        suspend fun request(s:Source):List<Entry>{delay(if(s.id.removePrefix("s").toInt()<5)400 else 20);return listOf(Entry(s.id,s.id,"星海远航",Kind.VIDEO,s.url))}
        val beforeStart=System.nanoTime();val oldSlots=Semaphore(3)
        // Reproduce 0.9.0's three-request queue and await-all display barrier.
        val before=coroutineScope{sources.map{s->async{oldSlots.withPermit{request(s)}}}.awaitAll().flatten()}
        val beforeMs=(System.nanoTime()-beforeStart)/1_000_000
        val afterStart=System.nanoTime();var firstMs=-1L;var final=emptyList<Entry>()
        SearchEngine().updates(sources,"星海"){s,_->request(s)}.collect{update->
            if(firstMs<0&&update.entries.isNotEmpty())firstMs=(System.nanoTime()-afterStart)/1_000_000
            final=update.entries
        }
        val allMs=(System.nanoTime()-afterStart)/1_000_000
        assertEquals(30,before.size);assertEquals(30,final.size);assertTrue("首个结果仍被慢源阻塞",firstMs in 0 until beforeMs)
        File("search091-controlled-benchmark.json").writeText(JSONObject().put("fixture","30 sources; 5 x 400ms and 25 x 20ms simulated responses")
            .put("beforeFirstVisibleMs",beforeMs).put("afterFirstVisibleMs",firstMs).put("afterAllDoneMs",allMs).put("finalResults",final.size)
            .put("realInternetSpeedClaim",false).toString(2))
    }
}
