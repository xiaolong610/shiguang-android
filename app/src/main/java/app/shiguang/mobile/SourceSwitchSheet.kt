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

data class SourceSwitchResult(val detail:Detail,val line:Int,val chapter:Int,val offset:Long)

@Composable internal fun SourceSwitchButton(model:AppModel,entry:Entry,detail:Detail?,onSwitched:(Entry)->Unit,onContinue:(SourceSwitchResult)->Unit={}){
    if(entry.sourceId==LocalNovels.source.id||entry.sourceId.startsWith("demo-")||entry.sourceId=="direct-video")return
    var open by remember(entry.key){mutableStateOf(false)}
    TextButton(onClick={open=true},contentPadding=PaddingValues(horizontal=0.dp)){
        Icon(Icons.Outlined.SwapHoriz,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("查找其他来源 / 换源",fontSize=12.sp)
    }
    if(open)SourceSwitchSheet(model,entry,detail,onDismiss={open=false}){result->
        model.applySourceSwitch(entry,result);open=false;onSwitched(result.detail.entry);onContinue(result)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SourceSwitchSheet(model:AppModel,entry:Entry,currentDetail:Detail?,
    progressOverride:Progress?=null,onDismiss:()->Unit,onSwitch:(SourceSwitchResult)->Unit){
    val scope=rememberCoroutineScope()
    val previous=progressOverride?:model.store.progressFor(entry.key)
    val previousTitle=previous?.chapterTitle?.takeIf{it.isNotBlank()}
        ?: previous?.let{currentDetail?.lines?.getOrNull(it.line)?.chapters?.getOrNull(it.chapter)?.title}.orEmpty()
    var query by remember(entry.key){mutableStateOf(entry.title)}
    var candidates by remember(entry.key){mutableStateOf(model.entries.filter{it.sourceId!=entry.sourceId&&WorkIdentity.same(entry,it)&&model.source(it.sourceId)?.enabled==true})}
    var busy by remember{mutableStateOf(false)};var searchJob by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var message by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    var target by remember{mutableStateOf<Detail?>(null)};var targetLine by remember{mutableIntStateOf(0)}
    var chapter by remember{mutableIntStateOf(0)};var loadingKey by remember{mutableStateOf<String?>(null)}
    val sources=model.sources.filter{it.enabled&&it.category==model.entryCategory(entry)&&it.id!=entry.sourceId&&it.engine!="demo"}
    fun search(){if(busy||query.isBlank())return;busy=true;error=null
        searchJob=scope.launch{try{var completed=0;var failures=0
            for(batch in sources.chunked(6)){
                val found=model.repo.search(batch,entry.kind,query.trim());completed+=batch.size;failures+=found.errors.size
                candidates=(candidates+found.entries).distinctBy{it.key}.sortedByDescending{WorkIdentity.same(entry,it)}
                message="已检查 $completed / ${sources.size} 个源"+(if(failures>0)" · $failures 个未能加载"else "")
            }
            if(candidates.isEmpty())message="没有找到其他来源，可修改关键词后重试。"
        }catch(e:CancellationException){throw e}catch(e:Exception){error=ContentRepository.friendlyError(e)}finally{busy=false}}
    }
    LaunchedEffect(entry.key){if(candidates.isEmpty())search()}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).navigationBarsPadding().padding(horizontal=20.dp)){
            Text(if(target==null)"其他来源"else"确认换源位置",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
            if(target==null){
                Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},singleLine=true,modifier=Modifier.weight(1f),label={Text("作品名称")},enabled=!busy);TextButton(onClick={candidates=emptyList();search()},enabled=!busy&&query.isNotBlank()){Text("搜索")}}
                Text("仅搜索已启用的同类源。请核对季数、年份和版本。",fontSize=11.sp,modifier=Modifier.padding(vertical=8.dp))
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message.ifBlank{if(sources.isEmpty())"请先启用其他同类内容源。"else"可选择已有结果，或搜索其他来源。"},fontSize=11.sp)
                error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
                LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(bottom=20.dp)){
                    items(candidates,key={it.key}){candidate->
                        ListItem(headlineContent={Text(candidate.title,fontSize=14.sp)},supportingContent={Text(model.source(candidate.sourceId)?.name?:"源已移除",fontSize=11.sp)},trailingContent={if(loadingKey==candidate.key)CircularProgressIndicator(Modifier.size(20.dp))},modifier=Modifier.clickable(enabled=loadingKey==null){
                            searchJob?.cancel();busy=false;loadingKey=candidate.key;error=null;scope.launch{try{
                                val d=model.repo.detail(model.source(candidate.sourceId)?.takeIf{it.enabled}?:error("源已停用或移除"),candidate)
                                require(d.lines.isNotEmpty()&&d.lines.all{it.chapters.isNotEmpty()}){"此来源未找到可用目录"}
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
                Text(if(previous==null)"选择开始的章节或集数。"else if(matched==null)"未能可靠匹配“$previousTitle”，请手动选择位置。"else"已匹配“${chapters[matched].title}”，可手动调整。",fontSize=12.sp)
                if(carryTime)Text("将沿用同集时间位置；不同剪辑可能需要调整。",fontSize=11.sp)
                if(entry.kind!=Kind.VIDEO&&previous!=null)Text("换源后从所选章节开头开始，原源阅读记录保留。",fontSize=11.sp)
                if(model.favorites.any{it.key==entry.key})Text("书架收藏将改为这个来源。",fontSize=11.sp)
                if(d.lines.size>1)ScrollableTabRow(targetLine,edgePadding=0.dp){d.lines.forEachIndexed{i,l->Tab(selected=i==targetLine,onClick={targetLine=i;chapter=ChapterIdentity.match(previousTitle,l.chapters,entry.kind)?:0},text={Text(l.name)})}}
                LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(vertical=8.dp)){
                    items(chapters.indices.toList(),key={it}){i->Row(Modifier.fillMaxWidth().clickable{chapter=i}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(chapter==i,{chapter=i});Text(chapters[i].title,fontSize=13.sp,modifier=Modifier.weight(1f))}}
                }
                Row(Modifier.fillMaxWidth().padding(bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TextButton(onClick={target=null}){Text("返回来源")}
                    Button(onClick={onSwitch(SourceSwitchResult(d,targetLine,chapter,if(carryTime)previous!!.offset else 0L))},modifier=Modifier.weight(1f)){Text("换源并继续")}
                }
            }
        }
    }
}
