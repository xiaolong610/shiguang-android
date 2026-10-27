package app.shiguang.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect

data class SourceSwitchResult(val detail:Detail,val line:Int,val chapter:Int,val offset:Long)

@Composable internal fun SourceSwitchButton(model:AppModel,entry:Entry,detail:Detail?,onSwitched:(Entry)->Unit,onContinue:(SourceSwitchResult)->Unit={}){
    if(entry.sourceId==LocalNovels.source.id||entry.sourceId.startsWith("demo-")||entry.sourceId=="direct-video")return
    var open by remember(entry.key){mutableStateOf(false)};val switchScope=rememberCoroutineScope();var switchError by remember{mutableStateOf<String?>(null)}
    TextButton(onClick={open=true},contentPadding=PaddingValues(horizontal=0.dp)){
        Icon(Icons.Outlined.SwapHoriz,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(tr(R.string.ui_0685),fontSize=12.sp)
    }
    if(open)SourceSwitchSheet(model,entry,detail,onDismiss={open=false}){result->
        switchScope.launch{try{model.applySourceSwitch(entry,result).await();open=false;onSwitched(result.detail.entry);onContinue(result)}catch(e:CancellationException){throw e}catch(e:Exception){switchError=ContentRepository.friendlyError(e)}}
    }
    switchError?.let{AlertDialog(onDismissRequest={switchError=null},title={Text(tr(R.string.ui_0740))},text={Text(it)},confirmButton={TextButton(onClick={switchError=null}){Text(tr(R.string.ui_0114))}})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SourceSwitchSheet(model:AppModel,entry:Entry,currentDetail:Detail?,
    progressOverride:Progress?=null,onDismiss:()->Unit,onSwitch:(SourceSwitchResult)->Unit){
    val scope=rememberCoroutineScope()
    val previous=progressOverride?:model.history.firstOrNull{it.entry.key==entry.key}
    val previousTitle=previous?.chapterTitle?.takeIf{it.isNotBlank()}
        ?: previous?.let{currentDetail?.lines?.getOrNull(it.line)?.chapters?.getOrNull(it.chapter)?.title}.orEmpty()
    var query by remember(entry.key){mutableStateOf(entry.title)}
    var candidates by remember(entry.key){mutableStateOf(model.entries.filter{it.sourceId!=entry.sourceId&&WorkIdentity.same(entry,it)&&model.source(it.sourceId)?.enabled==true})}
    var busy by remember{mutableStateOf(false)};var searchJob by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var checked by remember{mutableStateOf(emptySet<String>())};var failed by remember{mutableStateOf(emptySet<String>())};var searchedQuery by remember{mutableStateOf("")}
    var message by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    var target by remember{mutableStateOf<Detail?>(null)};var targetLine by remember{mutableIntStateOf(0)}
    var chapter by remember{mutableIntStateOf(0)};var loadingKey by remember{mutableStateOf<String?>(null)}
    val sources=model.sources.filter{it.enabled&&it.category==model.entryCategory(entry)&&it.id!=entry.sourceId&&it.engine!="demo"}
    fun search(){if(busy||query.isBlank())return;busy=true;error=null
        if(searchedQuery!=query){checked=emptySet();failed=emptySet();searchedQuery=query;candidates=emptyList()}
        searchJob=scope.launch{try{
            val cached=withContext(Dispatchers.IO){LibraryStore(model.getApplication()).use{db->db.sourceHealth() to db.searchCatalog(model.entryCategory(entry),sources)}}
            candidates=(candidates+withContext(Dispatchers.Default){SearchText.matches(cached.second,query,100)}).distinctBy{it.key}
            val ordered=withContext(Dispatchers.Default){SearchPlanner.order(sources,cached.first)}
            val batch=SearchPlanner.batch(ordered.filter{it.id !in checked},query,more=checked.isNotEmpty())
            withTimeoutOrNull(20_000){model.repo.searchUpdates(batch,entry.kind,query.trim(),onOutcome={outcome->try{withContext(Dispatchers.IO){LibraryStore(model.getApplication()).use{it.recordSearch(outcome)}}}catch(e:CancellationException){throw e}catch(_:Exception){}}).collect{update->
                checked=checked+update.outcomes.map{it.source.id};failed=failed+update.outcomes.filter{it.error!=null}.map{it.source.id}
                candidates=SearchText.rank((candidates+update.entries).distinctBy{it.key},query).sortedByDescending{WorkIdentity.same(entry,it)}
                message=tr(R.string.ui_0038, checked.size, sources.size)+(if(failed.isNotEmpty())tr(R.string.ui_0743, failed.size)else "")
            }}
            if(candidates.isEmpty())message=tr(R.string.ui_0744)
            if(checked.size<sources.size)message+=tr(R.string.ui_0745, sources.size-checked.size)
        }catch(e:CancellationException){throw e}catch(e:Exception){error=ContentRepository.friendlyError(e)}finally{busy=false}}
    }
    LaunchedEffect(entry.key){if(candidates.isEmpty())search()}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).navigationBarsPadding().padding(horizontal=20.dp)){
            Text(if(target==null)tr(R.string.ui_0746)else tr(R.string.ui_0747),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
            if(target==null){
                Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},singleLine=true,modifier=Modifier.weight(1f),label={Text(tr(R.string.ui_0748))},enabled=!busy);TextButton(onClick={search()},enabled=!busy&&query.isNotBlank()){Text(if(checked.isNotEmpty()&&searchedQuery==query&&checked.size<sources.size)tr(R.string.ui_0749)else tr(R.string.ui_0089))}}
                Text(tr(R.string.ui_0751),fontSize=11.sp,modifier=Modifier.padding(vertical=8.dp))
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message.ifBlank{if(sources.isEmpty())tr(R.string.ui_0752)else tr(R.string.ui_0753)},fontSize=11.sp)
                error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
                LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(bottom=20.dp)){
                    items(candidates,key={it.key}){candidate->
                        ListItem(headlineContent={Text(candidate.title,fontSize=14.sp)},supportingContent={Text(model.source(candidate.sourceId)?.name?:tr(R.string.ui_0396),fontSize=11.sp)},trailingContent={if(loadingKey==candidate.key)CircularProgressIndicator(Modifier.size(20.dp))},modifier=Modifier.clickable(enabled=loadingKey==null){
                            searchJob?.cancel();busy=false;loadingKey=candidate.key;error=null;scope.launch{try{
                                val d=model.repo.detail(model.source(candidate.sourceId)?.takeIf{it.enabled}?:error(tr(R.string.ui_0755)),candidate)
                                require(d.lines.isNotEmpty()&&d.lines.all{it.chapters.isNotEmpty()}){tr(R.string.ui_0756)}
                                targetLine=0;chapter=ChapterIdentity.match(previousTitle,d.lines[0].chapters,entry.kind)?:0;target=d
                            }catch(e:CancellationException){throw e}catch(e:Exception){error=ContentRepository.friendlyError(e)}finally{loadingKey=null}}
                        })
                    }
                }
            }else{
                val d=target!!;val chapters=d.lines[targetLine].chapters
                val matched=ChapterIdentity.match(previousTitle,chapters,entry.kind)
                val carryTime=entry.kind==Kind.VIDEO&&previous!=null&&matched==chapter
                Text("${d.entry.title} · ${model.source(d.entry.sourceId)?.name.orEmpty()}",fontSize=13.sp,modifier=Modifier.padding(vertical=10.dp))
                Text(if(previous==null)tr(R.string.ui_0757)else if(matched==null)tr(R.string.ui_0758, previousTitle)else tr(R.string.ui_0759, chapters[matched].title),fontSize=12.sp)
                if(carryTime)Text(tr(R.string.ui_0760),fontSize=11.sp)
                if(entry.kind!=Kind.VIDEO&&previous!=null)Text(tr(R.string.ui_0761),fontSize=11.sp)
                if(model.favorites.any{it.key==entry.key})Text(tr(R.string.ui_0762),fontSize=11.sp)
                if(d.lines.size>1)ScrollableTabRow(targetLine,edgePadding=0.dp){d.lines.forEachIndexed{i,l->Tab(selected=i==targetLine,onClick={targetLine=i;chapter=ChapterIdentity.match(previousTitle,l.chapters,entry.kind)?:0},text={Text(l.name)})}}
                LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(vertical=8.dp)){
                    items(chapters.indices.toList(),key={it}){i->Row(Modifier.fillMaxWidth().clickable{chapter=i}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(chapter==i,{chapter=i});Text(chapters[i].title,fontSize=13.sp,modifier=Modifier.weight(1f))}}
                }
                Row(Modifier.fillMaxWidth().padding(bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TextButton(onClick={target=null}){Text(tr(R.string.ui_0763))}
                    Button(onClick={onSwitch(SourceSwitchResult(d,targetLine,chapter,if(carryTime)previous!!.offset else 0L))},modifier=Modifier.weight(1f)){Text(tr(R.string.ui_0764))}
                }
            }
        }
    }
}
