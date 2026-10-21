package app.shiguang.mobile

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SourceSubscription(val id:String,val name:String,val url:String,val enabled:Boolean=true,val checked:Long=0,val succeeded:Long=0,val message:String="尚未刷新",val etag:String="",val modified:String="",val backup:String="[]") {
    fun json()=JSONObject().put("id",id).put("name",name).put("url",url).put("enabled",enabled).put("checked",checked).put("succeeded",succeeded).put("message",message).put("etag",etag).put("modified",modified).put("backup",backup)
    companion object {fun parse(o:JSONObject)=SourceSubscription(o.getString("id"),o.getString("name"),o.getString("url"),o.optBoolean("enabled",true),o.optLong("checked"),o.optLong("succeeded"),o.optString("message"),o.optString("etag"),o.optString("modified"),o.optString("backup","[]"))}
}
data class SubscriptionLink(val sourceId:String,val baseline:String,val removed:Boolean=false)
data class SubscriptionPlan(val sources:List<Source>,val links:List<SubscriptionLink>,val retainedEdits:Int,val retired:Int)

object SubscriptionMerge {
    private fun canonical(value:Any?):String=when(value) {
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.opt(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.opt(it))}
        null,JSONObject.NULL->"null"
        is String->JSONObject.quote(value)
        else->value.toString()
    }
    private fun content(s:Source)=canonical(s.json().apply{remove("enabled")})
    fun sameContent(a:Source,b:Source)=content(a)==content(b)
    fun plan(current:List<Source>,links:List<SubscriptionLink>,remote:List<Source>):SubscriptionPlan {
        val byId=current.associateBy{it.id};val baseline=links.associateBy{it.sourceId};val output=mutableListOf<Source>();val updatedLinks=links.associateBy{it.sourceId}.toMutableMap();var edits=0;var retired=0
        for(s in remote) {
            val link=baseline[s.id];val local=byId[s.id]
            if(link?.removed==true)continue
            val old=link?.let{runCatching{SourceCodec.parse(it.baseline).single()}.getOrNull()}
            val modified=local!=null&&old!=null&&content(local)!=content(old)
            val knownAds=JSONObject(s.rules).optBoolean("knownAds")
            val wasKnown=old?.let{JSONObject(it.rules).optBoolean("knownAds")} ?: false
            if(modified){output.add(local!!);edits++} else output.add(s.copy(enabled=if(knownAds&&!wasKnown)false else local?.enabled ?: s.enabled))
            // Keep the old baseline for locally edited rules, so refreshes never silently overwrite them.
            updatedLinks[s.id]=if(modified)link!! else SubscriptionLink(s.id,s.json().toString())
        }
        val remoteIds=remote.map{it.id}.toSet()
        for(link in links)if(link.sourceId !in remoteIds&&!link.removed)byId[link.sourceId]?.let{local->
            val old=runCatching{SourceCodec.parse(link.baseline).single()}.getOrNull()
            if(old!=null&&content(local)==content(old)){output.add(local.copy(enabled=false));retired++}else{output.add(local);edits++}
        }
        return SubscriptionPlan(output,updatedLinks.values.toList(),edits,retired)
    }
}

class SubscriptionManager(private val context:Context) {
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(18,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    companion object {private val mutex=Mutex()}
    suspend fun add(url:String):String {
        SourceCodec.httpUrl(url)
        val id=SourceCodec.stableId("subscription|$url")
        withContext(Dispatchers.IO){LibraryStore(context).use{store->if(store.subscriptions().none{it.id==id})store.putSubscription(SourceSubscription(id,url.toHttpUrl().host,url))}}
        return refresh(id)
    }
    suspend fun refreshAll(force:Boolean=false):List<String> {
        val subs=withContext(Dispatchers.IO){LibraryStore(context).use{it.subscriptions()}}
        return subs.filter{it.enabled&&(force||System.currentTimeMillis()-it.checked>6*60*60*1000)}.map{refresh(it.id)}
    }
    suspend fun refresh(id:String):String=mutex.withLock {withContext(Dispatchers.IO){LibraryStore(context).use {store->
        val sub=store.subscriptions().find{it.id==id} ?: return@use "订阅已移除"
        try {
            val builder=Request.Builder().url(sub.url).header("User-Agent","Shiguang/0.3")
            if(sub.etag.isNotBlank())builder.header("If-None-Match",sub.etag)
            if(sub.modified.isNotBlank())builder.header("If-Modified-Since",sub.modified)
            var etag="";var modified=""
            val raw=client.newCall(builder.build()).execute().use {response->
                if(response.code==304){val now=System.currentTimeMillis();store.transaction{store.subscriptions().find{it.id==id}?.let{store.putSubscription(it.copy(checked=now,succeeded=now,message="没有更新"))}};return@use null}
                require(response.isSuccessful){"HTTP ${response.code}"};etag=response.header("ETag").orEmpty();modified=response.header("Last-Modified").orEmpty()
                response.body?.byteStream()?.use{String(it.readBytesLimited(2*1024*1024),Charsets.UTF_8)} ?: error("订阅为空")
            } ?: return@use "没有更新"
            val trimmed=raw.trim().removePrefix("\uFEFF")
            val array=when {trimmed.startsWith('[')->JSONArray(trimmed);trimmed.startsWith('{')->JSONObject(trimmed).let{it.optJSONArray("sources") ?: JSONArray().put(it)};else->error("订阅必须返回JSON规则或源列表")}
            require(array.length() in 1..500){"订阅应包含1—500项"}
            val catalog=(0 until array.length()).all{val o=array.getJSONObject(it);o.has("name")&&!o.has("api")&&!o.has("url")&&!o.has("baseURL")&&!o.has("engine")}
            val sources=if(catalog)coroutineScope {
                val semaphore=Semaphore(3)
                (0 until array.length()).map{i->async{semaphore.withPermit{
                    val name=array.getJSONObject(i).getString("name");require(name.isNotBlank()&&!name.contains('/')&&!name.contains('\\')){"规则目录名称无效"}
                    val link=sub.url.toHttpUrl().newBuilder().removePathSegment(sub.url.toHttpUrl().pathSegments.lastIndex).addPathSegment("$name.json").build()
                    val text=client.newCall(Request.Builder().url(link).build()).execute().use{r->require(r.isSuccessful){"$name：HTTP ${r.code}"};r.body?.byteStream()?.use{String(it.readBytesLimited(512*1024),Charsets.UTF_8)} ?: error("规则为空")}
                    SourceCodec.parse(text).single()
                }}}.awaitAll()
            } else SourceCodec.parse(raw)
            for(s in sources)require(store.sourceOwner(s.id).let{it==null||it==id}){"${s.name}已由其他订阅管理，请先取消原订阅"}
            val now=System.currentTimeMillis()
            var message=""
            store.transaction {
                val latest=store.subscriptions().find{it.id==id} ?: return@transaction
                if(sub.enabled&&!latest.enabled){message="自动刷新已暂停，未应用更新";return@transaction}
                val current=store.sources();val links=store.subscriptionLinks(id)
                val plan=SubscriptionMerge.plan(current,links,sources)
                val before=JSONArray().apply{current.filter{old->links.any{it.sourceId==old.id}}.forEach{put(it.json())}}
                val changed=plan.sources.any{fresh->current.find{it.id==fresh.id}?.let{old->!SubscriptionMerge.sameContent(old,fresh)} ?: true}
                message="已检查 ${sources.size} 项"+(if(plan.retainedEdits>0)"，保留 ${plan.retainedEdits} 项本地修改" else "")+(if(plan.retired>0)"，停用 ${plan.retired} 项上游移除的源" else "")
                plan.sources.forEach{store.putSource(it)};plan.links.forEach{store.putSubscriptionLink(id,it)}
                store.putSubscription(latest.copy(checked=now,succeeded=now,message=message,etag=if(catalog)"" else etag,modified=if(catalog)"" else modified,backup=if(changed&&before.length()>0)before.toString() else latest.backup))
            };message.ifBlank{"订阅已移除"}
        } catch(e:CancellationException){throw e} catch(e:Exception){val message="刷新失败：${ContentRepository.friendlyError(e)}";store.subscriptions().find{it.id==id}?.let{store.putSubscription(it.copy(checked=System.currentTimeMillis(),message=message))};message}
    }}}
    suspend fun restore(id:String):String=mutex.withLock{withContext(Dispatchers.IO){LibraryStore(context).use{store->
        val sub=store.subscriptions().firstOrNull{it.id==id} ?: return@use "订阅已移除"
        val array=JSONArray(sub.backup);require(array.length()>0){"暂无上一版可恢复"}
        val old=SourceCodec.parse(array.toString());val links=store.subscriptionLinks(id).associateBy{it.sourceId};val current=store.sources().associateBy{it.id}
        store.transaction {old.filter{links[it.id]?.removed!=true}.forEach{s->store.putSource(s.copy(enabled=current[s.id]?.enabled ?: s.enabled));store.putSubscriptionLink(id,SubscriptionLink(s.id,s.json().toString()))};store.putSubscription(sub.copy(message="已恢复上一版；自动刷新已暂停",enabled=false,etag="",modified=""))}
        "已恢复上一版，自动刷新已暂停"
    }}}
}

class SubscriptionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try{val messages=SubscriptionManager(applicationContext).refreshAll(force=runAttemptCount>0);if(runAttemptCount<4&&messages.any{it.startsWith("刷新失败")})Result.retry() else Result.success()}catch(e:CancellationException){throw e}catch(e:Exception){Result.retry()}
}
object SubscriptionSchedule {
    fun ensure(context:Context) {
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work=PeriodicWorkRequestBuilder<SubscriptionWorker>(12,TimeUnit.HOURS,1,TimeUnit.HOURS).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("source-subscriptions",ExistingPeriodicWorkPolicy.KEEP,work)
        WorkManager.getInstance(context).enqueueUniqueWork("source-subscriptions-start",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SubscriptionWorker>().setConstraints(constraints).build())
    }
}
