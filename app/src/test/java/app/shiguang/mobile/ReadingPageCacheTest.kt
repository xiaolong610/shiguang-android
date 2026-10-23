package app.shiguang.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReadingPageCacheTest {
    @Test fun simultaneousForegroundAndPrefetchShareRequestAndCanceledWaiterDoesNotLoseResult()=runBlocking {
        val cache=ReadingPageCache();val count=AtomicInteger();val gate=CompletableDeferred<Unit>()
        try{
            val prefetch=async{cache.load("one"){count.incrementAndGet();gate.await();PageContent("正文")}}
            withTimeout(2000){while(count.get()==0)delay(5)}
            val foreground=async{cache.load("one"){error("duplicate request")}}
            prefetch.cancelAndJoin();gate.complete(Unit)
            assertEquals("正文",foreground.await().text);assertEquals(1,count.get())
            assertEquals("正文",cache.load("one"){error("should be cached")}.text)
        }finally{cache.close()}
    }
    @Test fun failedPrefetchCanRetryAndForcedReloadReplacesCachedPage()=runBlocking {
        val cache=ReadingPageCache()
        try{
            assertTrue(runCatching{cache.load("one"){error("network failure")}}.isFailure)
            assertEquals("成功",cache.load("one"){PageContent("成功")}.text)
            assertEquals("刷新",cache.load("one",true){PageContent("刷新")}.text)
        }finally{cache.close()}
    }
    @Test fun expiryAndLruBoundEvictOldContent()=runBlocking {
        var now=0L;val cache=ReadingPageCache(maxEntries=2,ttlMs=10,now={now})
        try{
            cache.load("a"){PageContent("A")};cache.load("b"){PageContent("B")};cache.load("c"){PageContent("C")}
            assertEquals("重新加载",cache.load("a"){PageContent("重新加载")}.text)
            now=11;assertEquals("到期刷新",cache.load("a"){PageContent("到期刷新")}.text)
        }finally{cache.close()}
    }
    @Test fun leavingAllReadersCancelsAbandonedNetworkWorkAndFreesLoadSlots()=runBlocking {
        val cache=ReadingPageCache();val started=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
        try{
            val old=async{cache.load("abandoned"){started.complete(Unit);try{awaitCancellation()}finally{stopped.complete(Unit)}}}
            started.await();old.cancelAndJoin();withTimeout(2000){stopped.await()}
            assertEquals("新章节",withTimeout(1000){cache.load("new"){PageContent("新章节")}}.text)
        }finally{cache.close()}
    }
}
