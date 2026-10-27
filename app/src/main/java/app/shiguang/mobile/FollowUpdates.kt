package app.shiguang.mobile

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FollowState(val entryKey:String,val titles:List<String>,val added:Set<String> = emptySet(),
    val lineName:String="",val checked:Long=0,val succeeded:Long=0,val error:String="") {
    fun json()=JSONObject().put("entryKey",entryKey).put("titles",JSONArray(titles)).put("added",JSONArray(added.toList()))
        .put("lineName",lineName).put("checked",checked).put("succeeded",succeeded).put("error",error)
    private fun readIndex(progress:Progress?):Int=progress?.takeIf{it.chapterTitle.isNotBlank()}?.let{p->titles.indices.filter{titles[it].substringBeforeLast('#')==ChapterIdentity.normalized(p.chapterTitle)}.singleOrNull()}
        ?: progress?.chapter ?: -1
    fun newCount(progress:Progress?):Int {val read=readIndex(progress);return titles.withIndex().count{it.index>read&&it.value in added}}
    fun unread(progress:Progress?)=(titles.size-readIndex(progress)-1).coerceAtLeast(0)
    companion object {
        fun parse(o:JSONObject):FollowState {
            fun strings(name:String)=o.optJSONArray(name)?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList()
            return FollowState(o.getString("entryKey"),strings("titles"),strings("added").toSet(),o.optString("lineName"),o.optLong("checked"),o.optLong("succeeded"),o.optString("error"))
        }
        fun observe(old:FollowState?,detail:Detail,line:Int,now:Long):FollowState {
            val selected=detail.lines[line.coerceIn(detail.lines.indices)]
            val keys=ChapterIdentity.keys(selected.chapters)
            // A different line is a new baseline, not a mass update.
            val previous=old?.takeIf{it.lineName==selected.name}
            val added=if(previous==null)emptySet() else (previous.added+(keys.toSet()-previous.titles.toSet())).intersect(keys.toSet())
            return FollowState(detail.entry.key,keys,added,selected.name,now,now)
        }
    }
}

class FollowManager(private val context:Context,private val repo:ContentRepository=ContentRepository(context),private val databaseName:String="shiguang.db") {
    companion object {private val mutex=Mutex()}
    suspend fun refresh(force:Boolean=false):String=mutex.withLock {
        withContext(Dispatchers.IO) {
            LibraryStore(context,databaseName).use{store->
                val sources=(Demo.sources+store.sources()).associateBy{it.id}
                val entries=store.favorites().filter{it.sourceId!=LocalNovels.source.id&&it.sourceId!="direct-video"}
                var success=0;var failed=0;var skipped=0
                for(entry in entries){
                    ensureActive()
                    val old=store.followState(entry.key)
                    if(!force&&old!=null&&System.currentTimeMillis()-old.checked<6*60*60*1000L){skipped++;continue}
                    try{
                        val source=sources[entry.sourceId]?.takeIf{it.enabled}?:error(tr(R.string.ui_0307))
                        val detail=repo.detail(source,entry,force=true)
                        require(detail.lines.isNotEmpty()&&detail.lines.any{it.chapters.isNotEmpty()}){tr(R.string.ui_0308)}
                        store.transaction{
                            if(store.favorites().none{it.key==entry.key})return@transaction
                            val progress=store.progressFor(entry.key)
                            val line=detail.lines.indexOfFirst{it.name==old?.lineName}.takeIf{it>=0}
                                ?: (progress?.line?:0).coerceIn(detail.lines.indices)
                            store.observeDetail(detail,line)
                        };success++
                    }catch(e:CancellationException){throw e}catch(e:Exception){
                        failed++
                        store.transaction{if(store.favorites().any{it.key==entry.key})store.putFollowState((store.followState(entry.key)?:FollowState(entry.key,emptyList())).copy(checked=System.currentTimeMillis(),error=ContentRepository.friendlyError(e)))}
                    }
                }
                tr(R.string.ui_0309, success)+(if(failed>0)tr(R.string.ui_0310, failed)else "")+(if(skipped>0)tr(R.string.ui_0311, skipped)else "")
            }
        }
    }
}

class FollowWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        if(!applicationContext.getSharedPreferences("follow",0).getBoolean("auto",true))return Result.success()
        return try{FollowManager(applicationContext).refresh();Result.success()}catch(e:CancellationException){throw e}catch(e:Exception){Result.retry()}
    }
}
object FollowSchedule {
    fun ensure(context:Context){
        val manager=WorkManager.getInstance(context)
        if(!context.getSharedPreferences("follow",0).getBoolean("auto",true)){manager.cancelUniqueWork("favorite-updates");manager.cancelUniqueWork("favorite-updates-start");return}
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        manager.enqueueUniquePeriodicWork("favorite-updates",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FollowWorker>(12,TimeUnit.HOURS).setConstraints(constraints).build())
        manager.enqueueUniqueWork("favorite-updates-start",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<FollowWorker>().setConstraints(constraints).build())
    }
}
