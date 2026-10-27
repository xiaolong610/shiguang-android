package app.shiguang.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

object SearchTraffic:AbstractCoroutineContextElement(Key){object Key:CoroutineContext.Key<SearchTraffic>}

data class SourceHealth(val sourceId:String,val latencyMs:Long=0,val failures:Int=0,val checked:Long=0,val fingerprint:String="")
data class SearchOutcome(val source:Source,val entries:List<Entry>,val error:String?=null,val elapsedMs:Long=0)
data class SearchUpdate(val outcomes:List<SearchOutcome>){
    val entries get()=outcomes.flatMap{it.entries}.distinctBy{it.key}
    val errors get()=outcomes.mapNotNull{it.error?.let{error->"${it.source.name}：$error"}}
}

object SearchPlanner {
    fun fingerprint(source:Source)=SourceCodec.stableId(source.copy(enabled=true).json().toString())
    fun dynamic(source:Source):Boolean {
        if(source.engine!="ficket")return false
        val r=runCatching{FicketRules.original(source)}.getOrDefault(JSONObject())
        val o=r.optJSONObject("searchRequestOptions")
        return r.optString("method")=="nightmare"||r.optBoolean("searchWebview")||o?.optBoolean("webview")==true||o?.optBoolean("mobileWebview")==true||r.optString("ruleSearchUrl").endsWith("@webview")
    }
    fun order(sources:List<Source>,health:Map<String,SourceHealth>,now:Long=System.currentTimeMillis()):List<Source> = sources.map{s->
        val h=health[s.id]?.takeIf{it.fingerprint==fingerprint(s)}
        val priority=when{s.engine=="demo"->0;h!=null&&h.failures>=2&&now-h.checked<15*60*1000L->4;dynamic(s)->3;h!=null&&h.failures==0&&h.latencyMs>0->1;else->2}
        Triple(s,priority,h?.takeIf{it.failures==0}?.latencyMs?:if(s.engine=="maccms")1500L else 3000L)
    }.sortedWith(compareBy<Triple<Source,Int,Long>>{it.second}.thenBy{it.third}).map{it.first}
    fun batch(sources:List<Source>,query:String,more:Boolean=false):List<Source> {
        val limit=if(more)24 else if(query.isBlank())6 else if(normalizeLength(query)==1)8 else 12
        val dynamicLimit=if(query.isBlank())1 else 2
        var dynamicCount=0
        return sources.filter{if(dynamic(it)){if(dynamicCount>=dynamicLimit)false else {dynamicCount++;true}}else true}.take(limit)
    }
    private fun normalizeLength(query:String)=SearchText.normalize(query).length
}

/** Independent lanes keep a page-action site from blocking fast HTTP sources. */
class SearchEngine(private val normalSlots:Semaphore=networkSlots,private val pageSlots:Semaphore=dynamicSlots,
    private val timeoutMs:Long=7000,private val dynamicTimeoutMs:Long=12000) {
    companion object {private val networkSlots=Semaphore(6);private val dynamicSlots=Semaphore(1)}
    fun updates(sources:List<Source>,query:String,onOutcome:suspend(SearchOutcome)->Unit={},loader:suspend(Source,String)->List<Entry>):Flow<SearchUpdate> = channelFlow {
        val mutex=Mutex();val completed=linkedMapOf<String,SearchOutcome>()
        sources.distinctBy{it.id}.forEach{s->launch(Dispatchers.Default){
            val dynamic=SearchPlanner.dynamic(s)
            (if(dynamic)pageSlots else normalSlots).withPermit{
                val start=System.nanoTime()
                val outcome=try{val entries=withTimeout(if(dynamic)dynamicTimeoutMs else timeoutMs){withContext(SearchTraffic){loader(s,query)}};SearchOutcome(s,entries,elapsedMs=(System.nanoTime()-start)/1_000_000)}
                catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();SearchOutcome(s,emptyList(),tr(R.string.ui_0717),(System.nanoTime()-start)/1_000_000)}
                catch(e:CancellationException){throw e}
                catch(e:Exception){SearchOutcome(s,emptyList(),ContentRepository.friendlyError(e),(System.nanoTime()-start)/1_000_000)}
                onOutcome(outcome)
                mutex.withLock{completed[s.id]=outcome;send(SearchUpdate(completed.values.toList()))}
            }
        }}
    }.conflate()
}
