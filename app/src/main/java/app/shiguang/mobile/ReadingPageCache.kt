package app.shiguang.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Bounded session cache, not an offline download library. Never cache failures. */
class ReadingPageCache(private val maxBytes:Int=12*1024*1024,private val maxEntries:Int=24,
    private val ttlMs:Long=30*60*1000L,private val now:()->Long={System.nanoTime()/1_000_000}) {
    private data class Cached(val page:PageContent,val weight:Int,val time:Long)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex();private val semaphore=Semaphore(2)
    private val ready=linkedMapOf<String,Cached>()
    private class Flight(val token:Any,val task:Deferred<PageContent>,var readers:Int=0,var cleanup:Job?=null)
    private val flights=mutableMapOf<String,Flight>()
    private var bytes=0
    suspend fun load(key:String,force:Boolean=false,loader:suspend ()->PageContent):PageContent {
        var hit:PageContent?=null
        val flight=mutex.withLock{
            val cached=ready.remove(key)
            if(cached!=null){bytes-=cached.weight;if(!force&&now()-cached.time<ttlMs){ready[key]=cached;bytes+=cached.weight;hit=cached.page;return@withLock null}}
            val existing=flights[key]?.takeIf{!it.task.isCancelled}
            val selected=existing?:run{
                val token=Any()
                val task=scope.async(start=CoroutineStart.LAZY){
                    try{
                        val page=semaphore.withPermit{loader()}
                        val weight=page.text.length*2+page.images.sumOf{it.length*2}+128
                        mutex.withLock{
                            if(weight<=maxBytes){ready.remove(key)?.let{bytes-=it.weight};ready[key]=Cached(page,weight,now());bytes+=weight
                                while(bytes>maxBytes||ready.size>maxEntries){val first=ready.entries.first();bytes-=first.value.weight;ready.remove(first.key)}}
                        };page
                    }finally{withContext(NonCancellable){mutex.withLock{if(flights[key]?.token===token)flights.remove(key)}}}
                }
                Flight(token,task).also{flights[key]=it}
            }
            selected.cleanup?.cancel();selected.cleanup=null;selected.readers++;selected
        }
        hit?.let{return it}
        val active=flight!!;active.task.start()
        try{return active.task.await()}
        finally{withContext(NonCancellable){mutex.withLock{
            active.readers--
            if(active.readers==0&&!active.task.isCompleted)active.cleanup=scope.launch{
                // Allow the next reader to adopt an in-flight prefetch during a chapter turn.
                delay(150)
                mutex.withLock{if(flights[key]===active&&active.readers==0){flights.remove(key);active.task.cancel()}}
            }
        }}}
    }
    fun close(){scope.cancel()}
    suspend fun clear(){mutex.withLock{ready.clear();bytes=0}}
    suspend fun sizeBytes():Long=mutex.withLock{bytes.toLong()}
    companion object {val shared=ReadingPageCache()}
}
