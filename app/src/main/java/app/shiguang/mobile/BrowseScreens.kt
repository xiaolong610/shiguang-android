package app.shiguang.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun CompactCategoryTabs(selected:ContentCategory,onSelect:(ContentCategory)->Unit,counts:Map<ContentCategory,Int>?=null,pills:Boolean=true){
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        (if(counts==null)ContentCategory.entries else listOf(ContentCategory.NOVEL,ContentCategory.COMIC,ContentCategory.VIDEO,ContentCategory.ANIME)).forEach{category->
            val active=selected==category
            Surface(onClick={onSelect(category)},modifier=Modifier.weight(1f).heightIn(min=44.dp).semantics{contentDescription=tr(R.string.ui_0086, category.label)},color=if(!pills)MaterialTheme.colorScheme.background else if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(10.dp)){
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(category.label+(counts?.let{" ${it[category]?:0}"}?:""),fontSize=if(pills)12.sp else 15.sp,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal,color=if(pills&&active)MaterialTheme.colorScheme.onPrimary else if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1);if(!pills){Spacer(Modifier.height(6.dp));Box(Modifier.width(34.dp).height(2.dp).background(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background))}}
            }
        }
    }
}

@Composable internal fun CompactDiscoverScreen(model:AppModel,onOpen:(Entry)->Unit,onAddRules:()->Unit,onDirect:()->Unit,onStorage:()->Unit={}){
    val context=LocalContext.current;val focus=LocalFocusManager.current;val scope=rememberCoroutineScope()
    var language by remember{mutableStateOf(false)}
    var adding by remember{mutableStateOf(false)};var importing by remember{mutableStateOf(false)};var issue by remember{mutableStateOf<String?>(null)}
    val cards=remember(model.entries,model.history){val history=model.history.associateBy{it.entry.key};WorkIdentity.groups(model.entries).map{group->group.maxByOrNull{e->history[e.key]?.updatedAt?:0L}!! to group.size}}
    val displayEntries=remember(cards){cards.map{it.first}}
    val sourceCounts=remember(cards){cards.associate{it.first.key to it.second}}
    var fullSearchMenu by remember{mutableStateOf(false)};var showErrors by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){importing=true;scope.launch{try{onOpen(model.importNovel(uri))}catch(e:CancellationException){throw e}catch(e:Exception){issue=ContentRepository.friendlyError(e)}finally{importing=false}}}}
    LaunchedEffect(model.sources,model.category,model.libraryReady,model.libraryRefreshing){model.ensureSearch()}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text(tr(R.string.ui_0087),fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(end=12.dp))
            Surface(modifier=Modifier.weight(1f).height(44.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Search,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));BasicTextField(value=model.query,onValueChange={model.editQuery(it)},singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(fontSize=13.sp,color=MaterialTheme.colorScheme.onSurface),modifier=Modifier.weight(1f),decorationBox={field->Box(contentAlignment=Alignment.CenterStart){if(model.query.isEmpty())Text(tr(R.string.ui_0088, model.category.label),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);field()}},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();model.search()}));IconButton(onClick={focus.clearFocus();model.search()}){Icon(Icons.Outlined.ArrowForward,tr(R.string.ui_0089),Modifier.size(20.dp))}}}
            Box{IconButton(onClick={adding=true},enabled=!importing){Icon(Icons.Outlined.Add,tr(R.string.ui_0090))};DropdownMenu(expanded=adding,onDismissRequest={adding=false}){
                DropdownMenuItem(text={Column{Text(tr(R.string.ui_0091));Text(tr(R.string.ui_0092),fontSize=11.sp)}},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},onClick={adding=false;picker.launch(arrayOf("*/*"))})
                DropdownMenuItem(text={Text(tr(R.string.language_menu))},leadingIcon={Icon(Icons.Outlined.Language,null)},onClick={adding=false;language=true})
                DropdownMenuItem(text={Text(tr(R.string.ui_0093))},leadingIcon={Icon(Icons.Outlined.Download,null)},onClick={adding=false;onStorage()})
                DropdownMenuItem(text={Text(tr(R.string.ui_0094))},leadingIcon={Icon(Icons.Outlined.Layers,null)},onClick={adding=false;onAddRules()})
                DropdownMenuItem(text={Text(tr(R.string.ui_0095))},leadingIcon={Icon(Icons.Outlined.Link,null)},onClick={adding=false;onDirect()})
            }}
        }
        CompactCategoryTabs(model.category,{model.changeCategory(it)},pills=false)
        if(model.libraryError.isNotBlank())Row(Modifier.padding(horizontal=16.dp)){Text(tr(R.string.ui_0096, model.libraryError),fontSize=11.sp,modifier=Modifier.weight(1f));TextButton(onClick={model.refreshLibrary()}){Text(tr(R.string.ui_0097))}}
        if(model.query.isNotBlank()&&model.suggestions.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0098),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);model.suggestions.forEach{title->TextButton(onClick={focus.clearFocus();model.search(title)},contentPadding=PaddingValues(horizontal=8.dp)){Text(title,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=150.dp))}}}
        if(importing){LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp));Text(tr(R.string.ui_0099),fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp))}
        model.history.firstOrNull{model.entryCategory(it.entry)==model.category}?.takeIf{!model.searched}?.let{progress->
            Surface(onClick={onOpen(progress.entry)},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.heightIn(min=40.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.PlayCircle,null,Modifier.size(18.dp));Text(tr(R.string.ui_0100, progress.entry.title),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).padding(horizontal=8.dp));Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(if(model.searched)tr(R.string.ui_0101)else tr(R.string.ui_0102),fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text(tr(R.string.ui_0103, displayEntries.size),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Row(Modifier.fillMaxWidth().heightIn(min=36.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(model.searchMessage,Modifier.weight(1f),fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(model.hasPendingResults)TextButton(onClick={model.revealSearchResults()}){Text(if(model.pendingResultCount>0)tr(R.string.ui_0104, model.pendingResultCount)else tr(R.string.ui_0105),fontSize=11.sp)}
            if(model.loading)TextButton(onClick={model.stopSearch()}){Text(tr(R.string.ui_0106),fontSize=11.sp)}
            else if(model.searchRemaining>0){TextButton(onClick={model.moreSearch()}){Text(tr(R.string.ui_0107, model.searchRemaining),fontSize=11.sp)};Box{IconButton(onClick={fullSearchMenu=true}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0108),Modifier.size(18.dp))};DropdownMenu(fullSearchMenu,{fullSearchMenu=false}){DropdownMenuItem(text={Text(tr(R.string.ui_0109))},onClick={fullSearchMenu=false;model.moreSearch(all=true)})}}}
        }
        if(model.loading&&model.entries.isNotEmpty())LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if(model.errors.isNotEmpty())Surface(onClick={showErrors=true},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(8.dp)){Text(tr(R.string.ui_0110, model.errors.size),fontSize=11.sp,modifier=Modifier.padding(8.dp))}
        when{
            model.loading&&model.entries.isEmpty()->Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            model.entries.isEmpty()->Column(Modifier.padding(24.dp)){Text(tr(R.string.ui_0111),fontSize=17.sp);Text(tr(R.string.ui_0112, model.category.label),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else->{val holder=rememberSaveableStateHolder();holder.SaveableStateProvider(model.category.name){CompactEntryGrid(displayEntries,onOpen=onOpen,sourceCounts=sourceCounts,onScroll={model.holdSearchResults()})}}
        }
    }
    if(language)LanguageDialog{language=false}
    issue?.let{AlertDialog(onDismissRequest={issue=null},title={Text(tr(R.string.ui_0113))},text={Text(it)},confirmButton={TextButton(onClick={issue=null}){Text(tr(R.string.ui_0114))}})}
    if(showErrors)AlertDialog(onDismissRequest={showErrors=false},title={Text(tr(R.string.ui_0115))},text={LazyColumn(Modifier.heightIn(max=360.dp)){items(model.errors){Text(it,fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))}}},confirmButton={TextButton(onClick={showErrors=false}){Text(tr(R.string.ui_0116))}})
}

@Composable internal fun CompactEntryGrid(entries:List<Entry>,progress:Map<String,Progress> = emptyMap(),onOpen:(Entry)->Unit,updates:Map<String,FollowState> = emptyMap(),sourceCounts:Map<String,Int> = emptyMap(),onScroll:()->Unit={}){
    val density=LocalDensity.current;val gridState=rememberLazyGridState();val scrollCallback by rememberUpdatedState(onScroll)
    LaunchedEffect(gridState){snapshotFlow{gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset}.distinctUntilChanged().collect{if(it.first>0||it.second>0)scrollCallback()}}
    BoxWithConstraints(Modifier.fillMaxSize()){
        val columns=if(maxWidth<350.dp||density.fontScale>1.25f)2 else (maxWidth.value/125f).toInt().coerceAtLeast(3)
        LazyVerticalGrid(columns=GridCells.Fixed(columns),state=gridState,contentPadding=PaddingValues(16.dp,0.dp,16.dp,16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(entries,key={it.key}){entry->Column(Modifier.testTag("entry:${entry.key}").clickable{onOpen(entry)}){
                Box {Cover(entry,Modifier.fillMaxWidth().aspectRatio(.8f));val count=updates[entry.key]?.newCount(progress[entry.key])?:0
                    if(count>0)Surface(color=MaterialTheme.colorScheme.primary,shape=RoundedCornerShape(6.dp),modifier=Modifier.align(Alignment.TopEnd).padding(5.dp)){Text(tr(R.string.ui_0117, count, if(entry.kind==Kind.VIDEO)tr(R.string.ui_0118)else if(entry.kind==Kind.COMIC)tr(R.string.ui_0119)else tr(R.string.ui_0120)),color=MaterialTheme.colorScheme.onPrimary,fontSize=10.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))}}
                Text(entry.title,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                val p=progress[entry.key]
                Text(if(p!=null)tr(if(entry.kind==Kind.VIDEO)R.string.position_episode else R.string.position_chapter,p.chapter+1)else entry.subtitle.ifBlank{tr(R.string.ui_0126)},fontSize=10.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
                updates[entry.key]?.let{state->Text(if(state.error.isNotBlank())tr(R.string.ui_0127)else tr(R.string.ui_0128, state.unread(p), if(entry.kind==Kind.VIDEO)tr(R.string.ui_0118)else if(entry.kind==Kind.COMIC)tr(R.string.ui_0119)else tr(R.string.ui_0120)),fontSize=10.sp,lineHeight=14.sp,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                sourceCounts[entry.key]?.takeIf{it>1}?.let{Text(tr(R.string.ui_0132, it),fontSize=10.sp,color=MaterialTheme.colorScheme.primary)}
            }}
        }
    }
}

@Composable internal fun CompactLibraryScreen(model:AppModel,onOpen:(Entry)->Unit){
    val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("browse-layout",0)}
    var categoryName by rememberSaveable{mutableStateOf(prefs.getString("libraryCategory","NOVEL")?:"NOVEL")}
    val category=ContentCategory.parse(categoryName)?:ContentCategory.NOVEL
    var history by rememberSaveable{mutableStateOf(false)};var searching by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")};var sorting by remember{mutableStateOf(false)};var alphabetical by rememberSaveable{mutableStateOf(false)}
    var onlyUpdates by rememberSaveable{mutableStateOf(false)}
    val selected=(if(history)model.history.map{it.entry}else model.favorites)
    val counts=ContentCategory.entries.associateWith{c->selected.count{model.entryCategory(it)==c}}
    val historyByKey=remember(model.history){model.history.associateBy{it.entry.key}}
    val list=selected.filter{model.entryCategory(it)==category&&(query.isBlank()||it.title.contains(query,true))&&(history||!onlyUpdates||(model.followStates[it.key]?.newCount(historyByKey[it.key])?:0)>0)}.let{if(alphabetical)it.sortedBy{e->e.title}else it}
    val holder=rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            if(searching)OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text(tr(R.string.ui_0133))},singleLine=true,modifier=Modifier.weight(1f),trailingIcon={IconButton(onClick={query="";searching=false}){Icon(Icons.Outlined.Close,tr(R.string.ui_0134))}})
            else {Text(tr(R.string.ui_0135),fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={model.checkUpdates()},enabled=!model.checkingUpdates){if(model.checkingUpdates)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Refresh,tr(R.string.ui_0136))};IconButton(onClick={searching=true}){Icon(Icons.Outlined.Search,tr(R.string.ui_0137))};Box{IconButton(onClick={sorting=true}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0138))};DropdownMenu(sorting,{sorting=false}){DropdownMenuItem(text={Text(tr(R.string.ui_0139))},onClick={alphabetical=false;sorting=false});DropdownMenuItem(text={Text(tr(R.string.ui_0140))},onClick={alphabetical=true;sorting=false});DropdownMenuItem(text={Text(if(model.autoFollow)tr(R.string.ui_0141)else tr(R.string.ui_0142))},onClick={model.updateAutoFollow(!model.autoFollow);sorting=false})}}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={history=false}){Text(tr(R.string.ui_0143),fontSize=18.sp,fontWeight=if(!history)FontWeight.Bold else FontWeight.Normal,color=if(!history)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={history=true}){Text(tr(R.string.ui_0144),fontSize=18.sp,fontWeight=if(history)FontWeight.Bold else FontWeight.Normal,color=if(history)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
        CompactCategoryTabs(category,onSelect={categoryName=it.name;prefs.edit().putString("libraryCategory",it.name).apply()},counts=counts)
        if(!history)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){FilterChip(selected=onlyUpdates,onClick={onlyUpdates=!onlyUpdates},label={Text(tr(R.string.ui_0145),fontSize=11.sp)});Text(if(model.checkingUpdates)tr(R.string.ui_0146)else model.updateMessage.ifBlank{if(model.autoFollow)tr(R.string.ui_0147)else tr(R.string.ui_0148)},fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f).padding(start=8.dp))}
        Spacer(Modifier.height(8.dp))
        if(list.isEmpty())Column(Modifier.padding(24.dp)){Text(if(!history&&onlyUpdates)tr(R.string.ui_0149)else if(history)tr(R.string.ui_0150, category.label)else tr(R.string.ui_0151, category.label),fontSize=17.sp);Text(if(!history&&onlyUpdates)tr(R.string.ui_0152)else tr(R.string.ui_0153),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        else holder.SaveableStateProvider("${category.name}:$history:$onlyUpdates"){CompactEntryGrid(list,model.history.associateBy{it.entry.key},onOpen,updates=if(history)emptyMap()else model.followStates)}
    }
}

@Composable internal fun CompactSourcesScreen(model:AppModel,onImport:()->Unit,notify:(String)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var subscriptions by rememberSaveable{mutableStateOf(false)};var categoryName by rememberSaveable{mutableStateOf("all")};val category=ContentCategory.parse(categoryName)
    var filtering by remember{mutableStateOf(false)};var searching by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")};var more by remember{mutableStateOf<String?>(null)};var info by remember{mutableStateOf<Source?>(null)};var deleting by remember{mutableStateOf<Source?>(null)};var testing by remember{mutableStateOf<String?>(null)};var changing by remember{mutableStateOf<Source?>(null)}
    val holder=rememberSaveableStateHolder()
    val visible=model.sources.filter{(category==null||it.category==category)&&(query.isBlank()||it.name.contains(query,true)||it.host.contains(query,true))}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)runCatching{context.contentResolver.openOutputStream(uri)?.use{it.write(JSONObject().put("version",1).put("sources",JSONArray().apply{model.sources.filter{it.engine!="demo"}.forEach{put(it.json())}}).toString(2).toByteArray())}}.fold({notify(tr(R.string.ui_0154))},{notify(tr(R.string.ui_0155, it.message))})}
    var exporting by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            if(searching)OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,placeholder={Text(tr(R.string.ui_0156))},modifier=Modifier.weight(1f),trailingIcon={IconButton(onClick={query="";searching=false}){Icon(Icons.Outlined.Close,tr(R.string.ui_0134))}})
            else {Text(tr(R.string.ui_0158),fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={searching=true}){Icon(Icons.Outlined.Search,tr(R.string.ui_0159))};IconButton(onClick=onImport){Icon(Icons.Outlined.Add,tr(R.string.ui_0160))};Box{IconButton(onClick={exporting=true}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0161))};DropdownMenu(exporting,{exporting=false}){DropdownMenuItem(text={Text(tr(R.string.ui_0162))},onClick={exporting=false;export.launch(tr(R.string.ui_0163))})}}}
        }
        Row(Modifier.padding(horizontal=8.dp)){TextButton(onClick={subscriptions=false}){Text(tr(R.string.ui_0164),fontSize=16.sp,fontWeight=if(!subscriptions)FontWeight.Bold else FontWeight.Normal)};TextButton(onClick={subscriptions=true}){Text(tr(R.string.ui_0165),fontSize=16.sp,fontWeight=if(subscriptions)FontWeight.Bold else FontWeight.Normal)}}
        if(subscriptions)Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)){SubscriptionPanel(model,notify)}
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                Box{TextButton(onClick={filtering=true},modifier=Modifier.semantics{contentDescription=tr(R.string.ui_0166)}){Text(category?.label?:tr(R.string.ui_0167));Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))};DropdownMenu(filtering,{filtering=false}){
                    DropdownMenuItem(text={Text(tr(R.string.ui_0168, model.sources.size))},onClick={categoryName="all";filtering=false})
                    ContentCategory.entries.forEach{c->DropdownMenuItem(text={Text("${c.label} · ${model.sources.count{it.category==c}}")},onClick={categoryName=c.name;filtering=false})}
                }}
                Spacer(Modifier.weight(1f));Text(tr(R.string.ui_0169, visible.count{it.enabled}, visible.size),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            holder.SaveableStateProvider(categoryName){LazyColumn(state=rememberLazyListState(),contentPadding=PaddingValues(horizontal=16.dp)){
                items(visible,key={it.id}){s->Column{
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).padding(vertical=6.dp)){Text(s.name,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${s.category.label} · ${if(s.engine=="demo")tr(R.string.ui_0170)else if(s.engine=="maccms")tr(R.string.ui_0171)else if(s.engine=="kazumi")tr(R.string.ui_0172)else tr(R.string.ui_0173)}"+(if(testing==s.id)tr(R.string.ui_0174)else if(JSONObject(s.rules).optBoolean("knownAds"))tr(R.string.ui_0175)else""),fontSize=10.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        Box(Modifier.size(52.dp,48.dp).toggleable(s.enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={model.toggle(s)}).semantics{contentDescription=tr(R.string.ui_0176, s.name)},contentAlignment=Alignment.Center){Switch(checked=s.enabled,onCheckedChange=null,modifier=Modifier.scale(.8f),colors=SwitchDefaults.colors(uncheckedTrackColor=MaterialTheme.colorScheme.surfaceVariant,uncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent))}
                        Box{IconButton(onClick={more=s.id}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0177, s.name),Modifier.size(20.dp))};DropdownMenu(more==s.id,{more=null}){
                            if(s.engine!="demo")DropdownMenuItem(text={Text(tr(R.string.ui_0178))},enabled=testing==null,onClick={more=null;testing=s.id;scope.launch{try{val result=runCatching{model.repo.searchOne(s,if(s.engine in setOf("html","kazumi"))"海贼王"else"")}.fold({tr(R.string.ui_0180, s.name, it.size)},{ContentRepository.friendlyError(it)});notify(result)}finally{testing=null}}})
                            DropdownMenuItem(text={Text(tr(R.string.ui_0181))},onClick={more=null;info=s})
                            if(s.kind==Kind.VIDEO&&s.engine!="demo")DropdownMenuItem(text={Text(tr(R.string.ui_0182))},onClick={more=null;changing=s})
                            if(s.engine!="demo")DropdownMenuItem(text={Text(tr(R.string.ui_0183))},onClick={more=null;deleting=s})
                        }}
                    };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                }}
                if(visible.isEmpty())item{Text(tr(R.string.ui_0184),Modifier.padding(vertical=24.dp),fontSize=14.sp)}
            }}
        }
    }
    info?.let{s->AlertDialog(onDismissRequest={info=null},title={Text(s.name)},text={Column{Text("${s.category.label} · ${s.engine}");Text(s.url.ifBlank{tr(R.string.ui_0185)},fontSize=12.sp);if(JSONObject(s.rules).optBoolean("knownAds"))Text(tr(R.string.ui_0186),fontSize=12.sp)}},confirmButton={TextButton(onClick={info=null}){Text(tr(R.string.ui_0116))}})}
    changing?.let{s->AlertDialog(onDismissRequest={changing=null},title={Text(tr(R.string.ui_0182))},text={Column{listOf(ContentCategory.VIDEO,ContentCategory.ANIME).forEach{c->TextButton(onClick={model.classify(s,c);changing=null}){Text(c.label)}}}},confirmButton={TextButton(onClick={changing=null}){Text(tr(R.string.ui_0189))}})}
    deleting?.let{s->AlertDialog(onDismissRequest={deleting=null},title={Text(tr(R.string.ui_0190))},text={Text(tr(R.string.ui_0191, s.name))},confirmButton={TextButton(onClick={model.remove(s);deleting=null}){Text(tr(R.string.ui_0192))}},dismissButton={TextButton(onClick={deleting=null}){Text(tr(R.string.ui_0189))}})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RuleImportSheet(model:AppModel,onClose:()->Unit,notify:(String)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var mode by rememberSaveable{mutableIntStateOf(0)};var input by rememberSaveable{mutableStateOf("")};var auto by rememberSaveable{mutableStateOf(true)};var busy by remember{mutableStateOf(false)};var issue by remember{mutableStateOf<String?>(null)}
    fun importText(text:String){runCatching{model.import(text)}.fold({notify(tr(R.string.ui_0194, it)+(if(model.lastImportDisabledAds>0)tr(R.string.ui_0195)else""));onClose()},{issue=ContentRepository.friendlyError(it)})}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)runCatching{context.contentResolver.openInputStream(uri)?.use{String(it.readBytesLimited(1024*1024),Charsets.UTF_8)}?:error(tr(R.string.ui_0196))}.fold({importText(it)},{issue=ContentRepository.friendlyError(it)})}
    Dialog(onDismissRequest={if(!busy)onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
            TopAppBar(title={Text(tr(R.string.ui_0094),fontSize=18.sp)},navigationIcon={IconButton(enabled=!busy,onClick=onClose){Icon(Icons.AutoMirrored.Outlined.ArrowBack,tr(R.string.ui_0198))}},windowInsets=WindowInsets(0,0,0,0))
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(tr(R.string.ui_0199),tr(R.string.ui_0200),tr(R.string.ui_0201)).forEachIndexed{i,label->FilterChip(selected=mode==i,onClick={mode=i;issue=null},label={Text(label)},enabled=!busy)}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)){
                when(mode){
                    0->{Text(tr(R.string.ui_0202),fontSize=18.sp);Text(tr(R.string.ui_0203),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp));OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/json","text/*","application/octet-stream"))}){Icon(Icons.Outlined.FolderOpen,null);Text(tr(R.string.ui_0204))}}
                    1->{OutlinedTextField(value=input,onValueChange={input=it;issue=null},singleLine=true,label={Text(tr(R.string.ui_0205))},placeholder={Text("https://…/sources.json")},modifier=Modifier.fillMaxWidth(),enabled=!busy);Text(tr(R.string.ui_0206),fontSize=12.sp,modifier=Modifier.padding(vertical=12.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0207),Modifier.weight(1f));Switch(auto,onCheckedChange={auto=it},enabled=!busy)};Text(tr(R.string.ui_0208),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    else->{OutlinedTextField(value=input,onValueChange={input=it;issue=null},label={Text(tr(R.string.ui_0209))},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=320.dp),enabled=!busy)}
                }
                issue?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.padding(top=16.dp))}
            }
            if(mode!=0)Button(enabled=!busy&&input.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(16.dp).height(48.dp),onClick={
                if(mode==2)importText(input) else {busy=true;scope.launch{try{val message=model.subscribe(input);if(AppLanguage.refreshFailed(message))issue=message else {if(!auto){val id=SourceCodec.stableId("subscription|${input.trim()}");model.subscriptions.firstOrNull{it.id==id}?.takeIf{it.enabled}?.let{model.toggleSubscription(it)}};notify(message);onClose()}}catch(e:CancellationException){throw e}catch(e:Exception){issue=ContentRepository.friendlyError(e)}finally{busy=false}}}
            }){Text(if(busy)tr(R.string.ui_0211)else if(mode==1)tr(R.string.ui_0212)else tr(R.string.ui_0213))}
        }}
    }
}
