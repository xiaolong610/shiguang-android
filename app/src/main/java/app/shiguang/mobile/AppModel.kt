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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

class AppModel(app: Application): AndroidViewModel(app) {
    val store=LibraryStore(app)
    val repo=ContentRepository(app)
    private val prefs=app.getSharedPreferences("source-options",0)
    private fun demoSources()=Demo.sources.map{it.copy(enabled=prefs.getBoolean(it.id,true))}
    var sources by mutableStateOf(demoSources());private set
    var favorites by mutableStateOf(emptyList<Entry>());private set
    var history by mutableStateOf(emptyList<Progress>());private set
    var kind by mutableStateOf(Kind.VIDEO);private set
    var category by mutableStateOf(ContentCategory.VIDEO);private set
    var entries by mutableStateOf(Demo.entries.filter { it.kind==kind });private set
    var query by mutableStateOf("")
    var loading by mutableStateOf(false);private set
    var errors by mutableStateOf(emptyList<String>());private set
    var searched by mutableStateOf(false);private set
    var searchCount by mutableStateOf(0);private set
    var searchTotal by mutableStateOf(0);private set
    var searchRemaining by mutableStateOf(0);private set
    var searchMessage by mutableStateOf("");private set
    var suggestions by mutableStateOf(emptyList<String>());private set
    var submittedQuery by mutableStateOf("");private set
    var hasPendingResults by mutableStateOf(false);private set
    var pendingResultCount by mutableStateOf(0);private set
    private var holdResults=false
    private var job: Job?=null
    private var suggestionJob:Job?=null
    private var generation=0
    private var sourceSignature=""
    private var catalog=emptyList<Entry>()
    private var catalogCategory:ContentCategory?=null
    private class SearchSession(val category:ContentCategory,val query:String,val sources:List<Source>){
        val seen=linkedMapOf<String,SearchOutcome>();val found=linkedMapOf<String,Entry>()
    }
    private var session:SearchSession?=null
    var lastImportDisabledAds=0;private set
    var subscriptions by mutableStateOf(emptyList<SourceSubscription>());private set
    var followStates by mutableStateOf(emptyMap<String,FollowState>());private set
    var checkingUpdates by mutableStateOf(false);private set
    var updateMessage by mutableStateOf("");private set
    var autoFollow by mutableStateOf(app.getSharedPreferences("follow",0).getBoolean("auto",true));private set
    var libraryReady by mutableStateOf(false);private set
    var libraryRefreshing by mutableStateOf(true);private set
    var libraryError by mutableStateOf("");private set
    private var observedRevision=LibraryRevision(-1,-1)
    private val writeCount=java.util.concurrent.atomic.AtomicInteger()
    val libraryIdle get()=libraryReady&&!libraryRefreshing&&writeCount.get()==0&&observedRevision==LibraryEvents.revision.value
    private data class Snapshot(val sources:List<Source>?,val subscriptions:List<SourceSubscription>?,val favorites:List<Entry>?,val history:List<Progress>?,val follows:Map<String,FollowState>?)
    init {
        viewModelScope.launch {
            try{withContext(Dispatchers.IO){
                if(!prefs.getBoolean("ad-source-review-v1",false)){
                    store.sources().filter{org.json.JSONObject(it.rules).optBoolean("knownAds")}.forEach{store.putSource(it.copy(enabled=false))}
                    prefs.edit().putBoolean("ad-source-review-v1",true).apply()
                }
                SubscriptionSchedule.ensure(app);FollowSchedule.ensure(app)
            }}catch(e:CancellationException){throw e}catch(e:Exception){libraryError=ContentRepository.friendlyError(e)}
            var read=LibraryRevision(-1,-1)
            LibraryEvents.revision.collect { revision->
                libraryRefreshing=true
                try{
                    delay(40);while(writeCount.get()>0)delay(10)
                    val data=withContext(Dispatchers.IO){Snapshot(
                        if(read.sources!=revision.sources)(demoSources()+store.sources()).also{rows->rows.forEach{it.category}}else null,
                        if(read.sources!=revision.sources)store.subscriptions()else null,
                        if(read.library!=revision.library)store.favorites()else null,
                        if(read.library!=revision.library)store.progress()else null,
                        if(read.library!=revision.library)store.followStates()else null)}
                    data.sources?.let{sources=it};data.subscriptions?.let{subscriptions=it};data.favorites?.let{favorites=it};data.history?.let{history=it};data.follows?.let{followStates=it}
                    read=revision;observedRevision=revision;libraryReady=true;libraryError=""
                }catch(e:CancellationException){throw e}catch(e:Exception){libraryError=ContentRepository.friendlyError(e)}
                finally{libraryRefreshing=false}
            }
        }
    }
    private val writes=kotlinx.coroutines.channels.Channel<()->Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val writerScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    private val writer=writerScope.launch{try{for(action in writes){try{action()}catch(e:Exception){withContext(Dispatchers.Main){libraryError=ContentRepository.friendlyError(e)}}finally{writeCount.decrementAndGet()}}}finally{store.close();writerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}}
    private fun write(sources:Boolean=false,library:Boolean=true,action:()->Unit):kotlinx.coroutines.Deferred<Unit>{
        val result=kotlinx.coroutines.CompletableDeferred<Unit>();libraryRefreshing=true;writeCount.incrementAndGet()
        val queued=writes.trySend{try{action();result.complete(Unit)}catch(e:Exception){result.completeExceptionally(e);throw e}finally{LibraryEvents.changed(sources,library)}}
        if(queued.isFailure){writeCount.decrementAndGet();result.completeExceptionally(IllegalStateException("页面已关闭，请重新打开后操作"))};return result
    }
    private suspend fun awaitLibrary(){while(!libraryIdle){if(libraryError.isNotBlank())error(libraryError);delay(10)}}
    private var indexedList:List<Source>?=null
    private var indexed=emptyMap<String,Source>()
    @Synchronized private fun sourceIndex():Map<String,Source>{val current=sources;if(indexedList!==current){indexed=current.associateBy{it.id};indexedList=current};return indexed}
    fun source(id: String)=sourceIndex()[id] ?: if(id=="direct-video") Source(id,"视频链接",Kind.VIDEO,"direct") else if(id==LocalNovels.source.id) LocalNovels.source else null
    suspend fun importNovel(uri:android.net.Uri):Entry {val e=LocalNovels(getApplication()).import(uri);withContext(Dispatchers.IO){store.favorite(e,true)};refreshLibrary();changeKind(Kind.NOVEL);return e}
    fun refreshLibrary() {libraryRefreshing=true;LibraryEvents.changed(library=true)}
    fun checkUpdates(){if(checkingUpdates)return;checkingUpdates=true;viewModelScope.launch{try{updateMessage=FollowManager(getApplication()).refresh(force=true);refreshLibrary()}catch(e:CancellationException){throw e}catch(e:Exception){updateMessage=ContentRepository.friendlyError(e)}finally{checkingUpdates=false}}}
    fun updateAutoFollow(value:Boolean){autoFollow=value;getApplication<Application>().getSharedPreferences("follow",0).edit().putBoolean("auto",value).apply();FollowSchedule.ensure(getApplication())}
    fun recordDetail(detail:Detail,line:Int=0){write{store.observeDetail(detail,line)}}
    fun applySourceSwitch(previous:Entry,result:SourceSwitchResult):kotlinx.coroutines.Deferred<Unit>{if(favorites.any{it.key==previous.key})favorites=favorites.filter{it.key!=previous.key}+result.detail.entry;return write{store.transaction{
        if(store.favorites().any{it.key==previous.key}){store.favorite(previous,false);store.favorite(result.detail.entry,true)}
        store.save(result.detail.entry,result.line,result.chapter,result.offset,result.detail.lines[result.line].chapters[result.chapter].title)
        store.observeDetail(result.detail,result.line)
    };val entry=result.detail.entry
        if(entry.kind==Kind.NOVEL)NovelPreferences(getApplication()).position(entry.key,result.chapter,0,0)
        if(entry.kind==Kind.COMIC)getApplication<Application>().getSharedPreferences("reader",0).edit().putString("comic:${entry.key}:${result.line}:${result.chapter}","0:0").apply()
        }}
    fun refreshSources(){libraryRefreshing=true;LibraryEvents.changed(sources=true)}
    suspend fun subscribe(url:String):String {val message=SubscriptionManager(getApplication()).add(url.trim());refreshSources();return message}
    suspend fun refreshSubscription(id:String):String {val message=SubscriptionManager(getApplication()).refresh(id);refreshSources();return message}
    suspend fun restoreSubscription(id:String):String {val message=SubscriptionManager(getApplication()).restore(id);refreshSources();return message}
    fun toggleSubscription(sub:SourceSubscription){write(sources=true,library=false){store.putSubscription(sub.copy(enabled=!sub.enabled))}}
    fun removeSubscription(sub:SourceSubscription){write(sources=true,library=false){store.deleteSubscription(sub.id)}}
    fun classify(source:Source,category:ContentCategory){require(source.kind==category.kind);val next=source.copy(rules=org.json.JSONObject(source.rules).put("category",category.name.lowercase()).toString());write(sources=true){store.preserveCategory(next);store.putSource(next)}}
    fun favorite(e: Entry,detail:Detail?=null) {val add=favorites.none{it.key==e.key};favorites=if(add)listOf(e)+favorites else favorites.filter{it.key!=e.key};write{store.favorite(e,add);if(detail!=null)store.observeDetail(detail)}}
    fun entryCategory(entry:Entry)=source(entry.sourceId)?.category ?: entry.category
    fun changeCategory(value:ContentCategory){if(category==value)return;category=value;kind=value.kind;search("")}
    fun changeKind(k: Kind) {changeCategory(ContentCategory.fromKind(k))}
    private fun signature()=category.name+sources.joinToString{it.hashCode().toString()}
    fun ensureSearch(){if(!libraryReady||libraryRefreshing)return;if(sourceSignature!=signature())search(if(query!=submittedQuery)submittedQuery else query)}
    fun editQuery(value:String){
        if(value==query)return
        if(loading)stopSearch()
        query=value.take(120);suggestionJob?.cancel();val typed=query;val selectedCategory=category
        suggestionJob=viewModelScope.launch{delay(220)
            val pool=withContext(Dispatchers.IO){if(catalogCategory==selectedCategory)catalog else LibraryStore(getApplication()).use{it.searchCatalog(selectedCategory,sources)}}
            val values=withContext(Dispatchers.Default){SearchText.suggestions(pool,typed)}
            if(query==typed&&category==selectedCategory)suggestions=values
        }
    }
    fun stopSearch(){job?.cancel();generation++;loading=false;searchRemaining=session?.let{it.sources.size-it.seen.size}?:searchTotal;searchMessage="已停止，保留当前结果"}
    fun search(q:String=query){
        job?.cancel();suggestionJob?.cancel();val current=++generation
        val text=q.trim().take(120);query=text;submittedQuery=text;searched=text.isNotBlank();loading=true
        holdResults=false;hasPendingResults=false;pendingResultCount=0
        entries=emptyList();errors=emptyList();searchCount=0;searchRemaining=0;searchMessage="正在读取本地匹配…"
        val selectedCategory=category
        job=viewModelScope.launch{
            try{
                awaitLibrary();sourceSignature=signature();val active=sources.filter{it.enabled&&it.category==selectedCategory};searchTotal=active.size;searchRemaining=active.size
                val data=withContext(Dispatchers.IO){LibraryStore(getApplication()).use{db->
                    val allowed=active.map{it.id}.toSet()
                    val saved=(db.favorites()+db.progress().map{it.entry}).filter{entryCategory(it)==selectedCategory&&(it.sourceId in allowed||it.sourceId==LocalNovels.source.id)}
                    val local=if(selectedCategory==ContentCategory.NOVEL)LocalNovels(getApplication()).entries()else emptyList()
                    Triple(db.sourceHealth(),(local+saved+db.searchCatalog(selectedCategory,active)).distinctBy{it.key},active)
                }}
                if(current!=generation)return@launch
                catalog=data.second;catalogCategory=selectedCategory
                val initial=withContext(Dispatchers.Default){if(text.isBlank())catalog.take(150)else SearchText.matches(catalog,text)}
                val ordered=withContext(Dispatchers.Default){SearchPlanner.order(data.third,data.first)}
                val next=SearchSession(selectedCategory,text,ordered)
                val initialSuggestions=withContext(Dispatchers.Default){SearchText.suggestions(catalog,text)}
                initial.forEach{next.found[it.key]=it};session=next;suggestions=initialSuggestions;entries=initial
                runSearchBatch(next,current,all=false,more=false,allowFuzzy=true)
            }catch(e:CancellationException){throw e}catch(e:Exception){if(current==generation){errors=listOf(ContentRepository.friendlyError(e));searchMessage="搜索暂时失败，已有结果保留"}}
            finally{if(current==generation)loading=false}
        }
    }
    fun holdSearchResults(){if(loading)holdResults=true}
    fun revealSearchResults(){val s=session?:return;holdResults=false;viewModelScope.launch{publish(s,generation)}}
    fun moreSearch(all:Boolean=false){
        if(loading)return
        val currentSession=session?:return
        if(query!=submittedQuery){search(query);return}
        val current=++generation;loading=true
        job=viewModelScope.launch{try{runSearchBatch(currentSession,current,all,more=true,allowFuzzy=false)}catch(e:CancellationException){throw e}catch(e:Exception){if(current==generation)searchMessage=ContentRepository.friendlyError(e)}finally{if(current==generation)loading=false}}
    }
    private suspend fun rememberOutcome(outcome:SearchOutcome){
        try{withContext(Dispatchers.IO){LibraryStore(getApplication()).use{it.recordSearch(outcome)}}}
        catch(e:CancellationException){throw e}catch(_:Exception){ /* Cache failure must not hide a live search result. */ }
    }
    private suspend fun publish(currentSession:SearchSession,current:Int){
        val ranked=withContext(Dispatchers.Default){SearchText.rank(currentSession.found.values.toList(),currentSession.query)}
        if(current!=generation)return
        if(holdResults){hasPendingResults=ranked!=entries;pendingResultCount=(ranked.size-entries.size).coerceAtLeast(0)}else{entries=ranked;hasPendingResults=false;pendingResultCount=0};searchCount=currentSession.seen.size;searchRemaining=currentSession.sources.size-searchCount
        errors=currentSession.seen.values.mapNotNull{o->o.error?.let{"${o.source.name}：$it"}}
    }
    private suspend fun runSearchBatch(currentSession:SearchSession,current:Int,all:Boolean,more:Boolean,allowFuzzy:Boolean){
        val pending=currentSession.sources.filter{it.id !in currentSession.seen}
        val selected=if(all)pending else SearchPlanner.batch(pending,currentSession.query,more)
        searchMessage=if(all)"正在搜索全部来源，可随时停止"else"快源优先，结果边到边显示"
        suspend fun collectResults(){repo.searchUpdates(selected,currentSession.category.kind,currentSession.query,::rememberOutcome).collect{update->
            if(current!=generation)return@collect
            update.outcomes.forEach{o->currentSession.seen[o.source.id]=o;o.entries.forEach{currentSession.found[it.key]=it}}
            publish(currentSession,current)
        }}
        if(all)collectResults()else withTimeoutOrNull(20_000){collectResults()}
        if(current!=generation)return
        if(allowFuzzy&&currentSession.found.isEmpty()&&SearchText.normalize(currentSession.query).length>=3){
            val successful=currentSession.seen.values.filter{it.error==null&&!SearchPlanner.dynamic(it.source)&&it.source.engine!="demo"}.map{it.source}.take(2)
            if(successful.isNotEmpty()){
                searchMessage="正在尝试少字和近似关键词…"
                val fallback=coroutineScope{SearchText.broader(currentSession.query).take(2).mapIndexed{i,variant->async(Dispatchers.Default){
                    val source=successful[i%successful.size]
                    val start=System.nanoTime()
                    try{withTimeoutOrNull(6000){withContext(SearchTraffic){repo.searchOne(source,variant)}}?.let{raw->
                        rememberOutcome(SearchOutcome(source,raw,elapsedMs=(System.nanoTime()-start)/1_000_000));SearchText.matches(raw,currentSession.query)
                    }?:emptyList()}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}
                }}.awaitAll().flatten()}
                fallback.forEach{currentSession.found[it.key]=it};publish(currentSession,current)
            }
        }
        if(current==generation){
            searchCount=currentSession.seen.size;searchRemaining=currentSession.sources.size-searchCount
            searchMessage=if(searchTotal==0)"暂无启用的同类网站，可先搜索本地作品"else"已检查 $searchCount / $searchTotal 个源"+(if(searchRemaining>0)"，可继续扩大范围"else"，搜索完成")
            catalog=(currentSession.found.values+catalog).distinctBy{it.key}.take(8000)
            if(!holdResults)suggestions=withContext(Dispatchers.Default){SearchText.suggestions(catalog,currentSession.query)}
        }
    }
    fun import(text: String): Int {
        val imported=SourceCodec.parse(text);lastImportDisabledAds=imported.count{!it.enabled&&org.json.JSONObject(it.rules).optBoolean("knownAds")};sources=(sources.filter{old->imported.none{it.id==old.id}}+imported);write(sources=true,library=false){store.putSources(imported)};return imported.size
    }
    fun toggle(s: Source) {
        if(s.engine=="demo") {prefs.edit().putBoolean(s.id,!s.enabled).apply();sources=sources.map { if(it.id==s.id) it.copy(enabled=!it.enabled) else it };LibraryEvents.changed(sources=true)}
        else {sources=sources.map{if(it.id==s.id)it.copy(enabled=!it.enabled)else it};write(sources=true,library=false){store.putSource(s.copy(enabled=!s.enabled))}}
    }
    fun remove(s: Source) {sources=sources.filter{it.id!=s.id};write(sources=true){store.preserveCategory(s);store.deleteSource(s.id)}}
    override fun onCleared() {super.onCleared();writes.close()}
}
