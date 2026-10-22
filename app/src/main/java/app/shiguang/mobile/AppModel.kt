package app.shiguang.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AppModel(app: Application): AndroidViewModel(app) {
    val store=LibraryStore(app)
    val repo=ContentRepository(app)
    private val prefs=app.getSharedPreferences("source-options",0)
    private fun demoSources()=Demo.sources.map{it.copy(enabled=prefs.getBoolean(it.id,true))}
    var sources by mutableStateOf(demoSources()+store.sources());private set
    var favorites by mutableStateOf(store.favorites());private set
    var history by mutableStateOf(store.progress());private set
    var kind by mutableStateOf(Kind.VIDEO);private set
    var category by mutableStateOf(ContentCategory.VIDEO);private set
    var entries by mutableStateOf(Demo.entries.filter { it.kind==kind });private set
    var query by mutableStateOf("")
    var loading by mutableStateOf(false);private set
    var errors by mutableStateOf(emptyList<String>());private set
    var searched by mutableStateOf(false);private set
    var searchCount by mutableStateOf(0);private set
    private var job: Job?=null
    private var generation=0
    var lastImportDisabledAds=0;private set
    var subscriptions by mutableStateOf(store.subscriptions());private set
    var followStates by mutableStateOf(store.followStates());private set
    var checkingUpdates by mutableStateOf(false);private set
    var updateMessage by mutableStateOf("");private set
    var autoFollow by mutableStateOf(app.getSharedPreferences("follow",0).getBoolean("auto",true));private set
    init {
        if(!prefs.getBoolean("ad-source-review-v1",false)){
            store.sources().filter{org.json.JSONObject(it.rules).optBoolean("knownAds")}.forEach{store.putSource(it.copy(enabled=false))}
            prefs.edit().putBoolean("ad-source-review-v1",true).apply()
            sources=demoSources()+store.sources()
        }
        SubscriptionSchedule.ensure(app)
        FollowSchedule.ensure(app)
    }
    fun source(id: String)=sources.find { it.id==id } ?: if(id=="direct-video") Source(id,"视频链接",Kind.VIDEO,"direct") else if(id==LocalNovels.source.id) LocalNovels.source else null
    suspend fun importNovel(uri:android.net.Uri):Entry {val e=LocalNovels(getApplication()).import(uri);store.favorite(e,true);refreshLibrary();changeKind(Kind.NOVEL);return e}
    fun refreshLibrary() { favorites=store.favorites();history=store.progress();followStates=store.followStates();refreshSources() }
    fun checkUpdates(){if(checkingUpdates)return;checkingUpdates=true;viewModelScope.launch{try{updateMessage=FollowManager(getApplication()).refresh(force=true);refreshLibrary()}catch(e:CancellationException){throw e}catch(e:Exception){updateMessage=ContentRepository.friendlyError(e)}finally{checkingUpdates=false}}}
    fun updateAutoFollow(value:Boolean){autoFollow=value;getApplication<Application>().getSharedPreferences("follow",0).edit().putBoolean("auto",value).apply();FollowSchedule.ensure(getApplication())}
    fun recordDetail(detail:Detail,line:Int=0){store.observeDetail(detail,line);followStates=store.followStates()}
    fun applySourceSwitch(previous:Entry,result:SourceSwitchResult){store.transaction{
        if(store.favorites().any{it.key==previous.key}){store.favorite(previous,false);store.favorite(result.detail.entry,true)}
        store.save(result.detail.entry,result.line,result.chapter,result.offset,result.detail.lines[result.line].chapters[result.chapter].title)
        store.observeDetail(result.detail,result.line)
    };val entry=result.detail.entry
        if(entry.kind==Kind.NOVEL)NovelPreferences(getApplication()).position(entry.key,result.chapter,0,0)
        if(entry.kind==Kind.COMIC)getApplication<Application>().getSharedPreferences("reader",0).edit().putString("comic:${entry.key}:${result.line}:${result.chapter}","0:0").apply()
        refreshLibrary()}
    fun refreshSources(){sources=demoSources()+store.sources();subscriptions=store.subscriptions()}
    suspend fun subscribe(url:String):String {val message=SubscriptionManager(getApplication()).add(url.trim());refreshSources();return message}
    suspend fun refreshSubscription(id:String):String {val message=SubscriptionManager(getApplication()).refresh(id);refreshSources();return message}
    suspend fun restoreSubscription(id:String):String {val message=SubscriptionManager(getApplication()).restore(id);refreshSources();return message}
    fun toggleSubscription(sub:SourceSubscription){store.putSubscription(sub.copy(enabled=!sub.enabled));refreshSources()}
    fun removeSubscription(sub:SourceSubscription){store.deleteSubscription(sub.id);refreshSources()}
    fun classify(source:Source,category:ContentCategory){require(source.kind==category.kind);val next=source.copy(rules=org.json.JSONObject(source.rules).put("category",category.name.lowercase()).toString());store.preserveCategory(next);store.putSource(next);refreshLibrary()}
    fun favorite(e: Entry,detail:Detail?=null) { store.favorite(e,favorites.none { it.key==e.key });if(detail!=null)store.observeDetail(detail);refreshLibrary() }
    fun entryCategory(entry:Entry)=source(entry.sourceId)?.category ?: entry.category
    fun changeCategory(value:ContentCategory){category=value;kind=value.kind;search("")}
    fun changeKind(k: Kind) {changeCategory(ContentCategory.fromKind(k))}
    fun search(q: String=query) {
        job?.cancel();val current=++generation;query=q;loading=true;errors=emptyList();entries=emptyList();searched=q.isNotBlank()
        job=viewModelScope.launch {
            try { val local=if(kind==Kind.NOVEL)kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){LocalNovels(getApplication()).entries().filter{q.isBlank()||it.title.contains(q,true)}}else emptyList();val r=repo.search(sources.filter{it.category==category},kind,q);if(current==generation){entries=local+r.entries;errors=r.errors;searchCount=r.searched} }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(current==generation) errors=listOf(ContentRepository.friendlyError(e)) }
            finally { if(current==generation) loading=false }
        }
    }
    fun import(text: String): Int {
        val imported=SourceCodec.parse(text);lastImportDisabledAds=imported.count{!it.enabled&&org.json.JSONObject(it.rules).optBoolean("knownAds")};store.putSources(imported);sources=demoSources()+store.sources();return imported.size
    }
    fun toggle(s: Source) {
        if(s.engine=="demo") {prefs.edit().putBoolean(s.id,!s.enabled).apply();sources=sources.map { if(it.id==s.id) it.copy(enabled=!it.enabled) else it }}
        else {store.putSource(s.copy(enabled=!s.enabled));sources=sources.filter { it.engine=="demo" }+store.sources()}
    }
    fun remove(s: Source) {store.preserveCategory(s);store.deleteSource(s.id);sources=sources.filter { it.id!=s.id };refreshLibrary()}
    override fun onCleared() { super.onCleared();store.close() }
}
