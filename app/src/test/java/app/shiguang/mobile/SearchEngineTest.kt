package app.shiguang.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SearchEngineTest {
    private fun source(id:String)=Source(id,id,Kind.VIDEO,"maccms","https://example.com/$id")
    private fun entry(s:Source)=Entry(s.id,s.id,"星海远航",Kind.VIDEO,s.url)
    @Test fun fastResultIsEmittedWhileSlowSourceIsStillRunning()=runBlocking {
        val slow=source("slow");val fast=source("fast");val gate=CompletableDeferred<Unit>();val first=CompletableDeferred<SearchUpdate>()
        val task=async{SearchEngine().updates(listOf(slow,fast),"星海"){s,_->if(s==slow)gate.await();listOf(entry(s))}.onEach{if(it.entries.any{e->e.sourceId==fast.id})first.complete(it)}.last()}
        val partial=withTimeout(3000){first.await()}
        assertEquals(listOf("fast"),partial.entries.map{it.sourceId});assertFalse(task.isCompleted)
        gate.complete(Unit);assertEquals(2,task.await().entries.size)
    }
    @Test fun dynamicPageQueueDoesNotOccupyHttpSlots()=runBlocking {
        val gate=CompletableDeferred<Unit>();val fast=source("fast")
        val dynamic=(1..10).map{source("page$it").copy(engine="ficket",rules="""{"original":{"method":"nightmare"}}""")}
        val first=withTimeout(3000){SearchEngine(Semaphore(2),Semaphore(1)).updates(dynamic+fast,"星海"){s,_->if(s.engine=="ficket")gate.await();listOf(entry(s))}.first{it.entries.any{e->e.sourceId==fast.id}}}
        assertTrue(first.entries.any{it.sourceId==fast.id})
    }
    @Test fun individualTimeoutRetainsOtherResults()=runBlocking {
        val slow=source("slow");val fast=source("fast")
        val last=SearchEngine(Semaphore(2),Semaphore(1),timeoutMs=120).updates(listOf(slow,fast),"星海"){s,_->if(s==slow)delay(1000);listOf(entry(s))}.last()
        assertEquals(listOf("fast"),last.entries.map{it.sourceId});assertEquals(1,last.errors.size)
    }
    @Test fun cancelingSearchActuallyCancelsTheHttpCall()=runBlocking {
        val server=MockWebServer();server.start();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try{
            val task=async(Dispatchers.Default){ContentRepository().fetch(server.url("/slow").toString())}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));withTimeout(2000){task.cancelAndJoin()}
            withTimeout(2000){while(ContentNetwork.foreground.dispatcher.runningCallsCount()>0)delay(10)}
            server.enqueue(MockResponse().setBody("new query"))
            assertEquals("new query",ContentRepository().fetch(server.url("/new").toString()))
        }finally{server.shutdown()}
    }
    @Test fun catalogCacheReusesSuccessButExplicitRefreshStillFetchesFreshDirectory()=runBlocking {
        val server=MockWebServer();server.start()
        val s=Source("html","站点",Kind.NOVEL,"html",server.url("/").toString(),rules="""{"chapters":"a"}""")
        val e=Entry("book",s.id,"星海远航",Kind.NOVEL,server.url("/book").toString())
        try{
            server.enqueue(MockResponse().setBody("<a href='/one'>第一章</a>"))
            val repo=ContentRepository();assertEquals(1,repo.detail(s,e).lines.single().chapters.size)
            assertEquals(1,repo.detail(s,e).lines.single().chapters.size);assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody("<a href='/one'>第一章</a><a href='/two'>第二章</a>"))
            assertEquals(2,repo.detail(s,e,force=true).lines.single().chapters.size);assertEquals(2,server.requestCount)
        }finally{server.shutdown()}
    }
}
