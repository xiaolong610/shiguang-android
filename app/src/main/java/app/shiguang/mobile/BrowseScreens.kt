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
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun CompactCategoryTabs(selected:ContentCategory,onSelect:(ContentCategory)->Unit,counts:Map<ContentCategory,Int>?=null,pills:Boolean=true){
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        (if(counts==null)ContentCategory.entries else listOf(ContentCategory.NOVEL,ContentCategory.COMIC,ContentCategory.VIDEO,ContentCategory.ANIME)).forEach{category->
            val active=selected==category
            Surface(onClick={onSelect(category)},modifier=Modifier.weight(1f).heightIn(min=44.dp).semantics{contentDescription="切换${category.label}分类"},color=if(!pills)MaterialTheme.colorScheme.background else if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(10.dp)){
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(category.label+(counts?.let{" ${it[category]?:0}"}?:""),fontSize=if(pills)12.sp else 15.sp,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal,color=if(pills&&active)MaterialTheme.colorScheme.onPrimary else if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1);if(!pills){Spacer(Modifier.height(6.dp));Box(Modifier.width(34.dp).height(2.dp).background(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background))}}
            }
        }
    }
}

@Composable internal fun CompactDiscoverScreen(model:AppModel,onOpen:(Entry)->Unit,onAddRules:()->Unit,onDirect:()->Unit){
    val context=LocalContext.current;val focus=LocalFocusManager.current;val scope=rememberCoroutineScope()
    var adding by remember{mutableStateOf(false)};var importing by remember{mutableStateOf(false)};var issue by remember{mutableStateOf<String?>(null)}
    val groups=remember(model.entries){WorkIdentity.groups(model.entries)}
    val displayEntries=groups.map{group->group.maxByOrNull{e->model.history.find{it.entry.key==e.key}?.updatedAt?:0L}!!}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){importing=true;scope.launch{try{onOpen(model.importNovel(uri))}catch(e:CancellationException){throw e}catch(e:Exception){issue=ContentRepository.friendlyError(e)}finally{importing=false}}}}
    LaunchedEffect(model.sources){model.search()}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("拾光",fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(end=12.dp))
            Surface(modifier=Modifier.weight(1f).height(44.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Search,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));BasicTextField(value=model.query,onValueChange={model.query=it},singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(fontSize=13.sp,color=MaterialTheme.colorScheme.onSurface),modifier=Modifier.weight(1f),decorationBox={field->Box(contentAlignment=Alignment.CenterStart){if(model.query.isEmpty())Text("搜索${model.category.label}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);field()}},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();model.search()}));IconButton(onClick={focus.clearFocus();model.search()}){Icon(Icons.Outlined.ArrowForward,"搜索",Modifier.size(20.dp))}}}
            Box{IconButton(onClick={adding=true},enabled=!importing){Icon(Icons.Outlined.Add,"添加")};DropdownMenu(expanded=adding,onDismissRequest={adding=false}){
                DropdownMenuItem(text={Column{Text("导入本地小说");Text("常见格式 · 最大100MiB",fontSize=11.sp)}},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},onClick={adding=false;picker.launch(arrayOf("*/*"))})
                DropdownMenuItem(text={Text("添加内容源")},leadingIcon={Icon(Icons.Outlined.Layers,null)},onClick={adding=false;onAddRules()})
                DropdownMenuItem(text={Text("打开视频链接")},leadingIcon={Icon(Icons.Outlined.Link,null)},onClick={adding=false;onDirect()})
            }}
        }
        CompactCategoryTabs(model.category,{model.changeCategory(it)},pills=false)
        if(importing){LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp));Text("正在整理小说…",fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp))}
        model.history.firstOrNull{model.entryCategory(it.entry)==model.category}?.takeIf{!model.searched}?.let{progress->
            Surface(onClick={onOpen(progress.entry)},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.heightIn(min=40.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.PlayCircle,null,Modifier.size(18.dp));Text("继续：${progress.entry.title}",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).padding(horizontal=8.dp));Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(if(model.searched)"搜索结果"else"最近更新",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("${displayEntries.size} 部",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(model.errors.isNotEmpty()){var expanded by remember{mutableStateOf(false)};Surface(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(8.dp)){Column(Modifier.padding(10.dp)){Text("${model.errors.size} 个源未能加载 · ${if(expanded)"收起"else"查看"}",fontSize=11.sp);if(expanded)model.errors.forEach{Text(it,fontSize=11.sp)}}}}
        when{
            model.loading->Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            model.entries.isEmpty()->Column(Modifier.padding(24.dp)){Text("暂时没有内容",fontSize=17.sp);Text("添加或启用${model.category.label}规则，或换个关键词。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else->{val holder=rememberSaveableStateHolder();holder.SaveableStateProvider(model.category.name){CompactEntryGrid(displayEntries,onOpen=onOpen,sourceCounts=displayEntries.associate{e->e.key to (groups.find{g->g.any{it.key==e.key}}?.size?:1)})}}
        }
    }
    issue?.let{AlertDialog(onDismissRequest={issue=null},title={Text("未能导入小说")},text={Text(it)},confirmButton={TextButton(onClick={issue=null}){Text("知道了")}})}
}

@Composable internal fun CompactEntryGrid(entries:List<Entry>,progress:Map<String,Progress> = emptyMap(),onOpen:(Entry)->Unit,updates:Map<String,FollowState> = emptyMap(),sourceCounts:Map<String,Int> = emptyMap()){
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()){
        val columns=if(maxWidth<350.dp||density.fontScale>1.25f)2 else (maxWidth.value/125f).toInt().coerceAtLeast(3)
        LazyVerticalGrid(columns=GridCells.Fixed(columns),state=rememberLazyGridState(),contentPadding=PaddingValues(16.dp,0.dp,16.dp,16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(entries,key={it.key}){entry->Column(Modifier.clickable{onOpen(entry)}){
                Box {Cover(entry,Modifier.fillMaxWidth().aspectRatio(.8f));val count=updates[entry.key]?.newCount(progress[entry.key])?:0
                    if(count>0)Surface(color=MaterialTheme.colorScheme.primary,shape=RoundedCornerShape(6.dp),modifier=Modifier.align(Alignment.TopEnd).padding(5.dp)){Text("新增 $count ${if(entry.kind==Kind.VIDEO)"集"else if(entry.kind==Kind.COMIC)"话"else"章"}",color=MaterialTheme.colorScheme.onPrimary,fontSize=10.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))}}
                Text(entry.title,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                val p=progress[entry.key]
                Text(if(p!=null)"${if(entry.kind==Kind.NOVEL)"读到第"else"看到第"} ${p.chapter+1} ${if(entry.kind==Kind.NOVEL)"章"else if(entry.kind==Kind.COMIC)"话"else"集"}"else entry.subtitle.ifBlank{"尚未开始"},fontSize=10.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
                updates[entry.key]?.let{state->Text(if(state.error.isNotBlank())"检查失败 · 保留上次结果"else "未读 ${state.unread(p)} ${if(entry.kind==Kind.VIDEO)"集"else if(entry.kind==Kind.COMIC)"话"else"章"}",fontSize=10.sp,lineHeight=14.sp,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                sourceCounts[entry.key]?.takeIf{it>1}?.let{Text("$it 个来源",fontSize=10.sp,color=MaterialTheme.colorScheme.primary)}
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
    LaunchedEffect(Unit){while(true){model.refreshLibrary();kotlinx.coroutines.delay(15000)}}
    val selected=(if(history)model.history.map{it.entry}else model.favorites)
    val counts=ContentCategory.entries.associateWith{c->selected.count{model.entryCategory(it)==c}}
    val list=selected.filter{model.entryCategory(it)==category&&(query.isBlank()||it.title.contains(query,true))&&(history||!onlyUpdates||(model.followStates[it.key]?.newCount(model.history.find{p->p.entry.key==it.key})?:0)>0)}.let{if(alphabetical)it.sortedBy{e->e.title}else it}
    val holder=rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            if(searching)OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("搜索收藏")},singleLine=true,modifier=Modifier.weight(1f),trailingIcon={IconButton(onClick={query="";searching=false}){Icon(Icons.Outlined.Close,"收起搜索")}})
            else {Text("书架",fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={model.checkUpdates()},enabled=!model.checkingUpdates){if(model.checkingUpdates)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Refresh,"检查收藏更新")};IconButton(onClick={searching=true}){Icon(Icons.Outlined.Search,"搜索书架")};Box{IconButton(onClick={sorting=true}){Icon(Icons.Outlined.MoreVert,"书架排序")};DropdownMenu(sorting,{sorting=false}){DropdownMenuItem(text={Text("最近收藏 / 浏览")},onClick={alphabetical=false;sorting=false});DropdownMenuItem(text={Text("按名称排序")},onClick={alphabetical=true;sorting=false});DropdownMenuItem(text={Text(if(model.autoFollow)"关闭自动追更"else"开启自动追更")},onClick={model.updateAutoFollow(!model.autoFollow);sorting=false})}}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={history=false}){Text("收藏",fontSize=18.sp,fontWeight=if(!history)FontWeight.Bold else FontWeight.Normal,color=if(!history)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={history=true}){Text("最近",fontSize=18.sp,fontWeight=if(history)FontWeight.Bold else FontWeight.Normal,color=if(history)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
        CompactCategoryTabs(category,onSelect={categoryName=it.name;prefs.edit().putString("libraryCategory",it.name).apply()},counts=counts)
        if(!history)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){FilterChip(selected=onlyUpdates,onClick={onlyUpdates=!onlyUpdates},label={Text("有更新",fontSize=11.sp)});Text(if(model.checkingUpdates)"正在检查收藏目录…"else model.updateMessage.ifBlank{if(model.autoFollow)"自动追更已开启 · 约每12小时"else"自动追更已关闭"},fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f).padding(start=8.dp))}
        Spacer(Modifier.height(8.dp))
        if(list.isEmpty())Column(Modifier.padding(24.dp)){Text(if(!history&&onlyUpdates)"暂时没有新的更新"else if(history)"还没有${category.label}足迹"else"还没有收藏${category.label}",fontSize=17.sp);Text(if(!history&&onlyUpdates)"点击右上角刷新检查，或关闭“有更新”查看全部收藏。"else"打开内容详情，点击右上角收藏即可加入。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        else holder.SaveableStateProvider("${category.name}:$history:$onlyUpdates"){CompactEntryGrid(list,model.history.associateBy{it.entry.key},onOpen,updates=if(history)emptyMap()else model.followStates)}
    }
}

@Composable internal fun CompactSourcesScreen(model:AppModel,onImport:()->Unit,notify:(String)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var subscriptions by rememberSaveable{mutableStateOf(false)};var categoryName by rememberSaveable{mutableStateOf("all")};val category=ContentCategory.parse(categoryName)
    var filtering by remember{mutableStateOf(false)};var searching by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")};var more by remember{mutableStateOf<String?>(null)};var info by remember{mutableStateOf<Source?>(null)};var deleting by remember{mutableStateOf<Source?>(null)};var testing by remember{mutableStateOf<String?>(null)};var changing by remember{mutableStateOf<Source?>(null)}
    val holder=rememberSaveableStateHolder()
    val visible=model.sources.filter{(category==null||it.category==category)&&(query.isBlank()||it.name.contains(query,true)||it.host.contains(query,true))}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)runCatching{context.contentResolver.openOutputStream(uri)?.use{it.write(JSONObject().put("version",1).put("sources",JSONArray().apply{model.sources.filter{it.engine!="demo"}.forEach{put(it.json())}}).toString(2).toByteArray())}}.fold({notify("源配置已导出")},{notify("导出失败：${it.message}")})}
    var exporting by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            if(searching)OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,placeholder={Text("搜索规则名称或网站")},modifier=Modifier.weight(1f),trailingIcon={IconButton(onClick={query="";searching=false}){Icon(Icons.Outlined.Close,"收起搜索")}})
            else {Text("内容源",fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={searching=true}){Icon(Icons.Outlined.Search,"搜索内容源")};IconButton(onClick=onImport){Icon(Icons.Outlined.Add,"导入内容源")};Box{IconButton(onClick={exporting=true}){Icon(Icons.Outlined.MoreVert,"内容源更多")};DropdownMenu(exporting,{exporting=false}){DropdownMenuItem(text={Text("导出内容源")},onClick={exporting=false;export.launch("拾光-内容源.json")})}}}
        }
        Row(Modifier.padding(horizontal=8.dp)){TextButton(onClick={subscriptions=false}){Text("网站规则",fontSize=16.sp,fontWeight=if(!subscriptions)FontWeight.Bold else FontWeight.Normal)};TextButton(onClick={subscriptions=true}){Text("订阅",fontSize=16.sp,fontWeight=if(subscriptions)FontWeight.Bold else FontWeight.Normal)}}
        if(subscriptions)Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)){SubscriptionPanel(model,notify)}
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                Box{TextButton(onClick={filtering=true},modifier=Modifier.semantics{contentDescription="选择规则分类"}){Text(category?.label?:"全部类型");Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))};DropdownMenu(filtering,{filtering=false}){
                    DropdownMenuItem(text={Text("全部类型 · ${model.sources.size}")},onClick={categoryName="all";filtering=false})
                    ContentCategory.entries.forEach{c->DropdownMenuItem(text={Text("${c.label} · ${model.sources.count{it.category==c}}")},onClick={categoryName=c.name;filtering=false})}
                }}
                Spacer(Modifier.weight(1f));Text("${visible.count{it.enabled}} 启用 / ${visible.size} 条",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            holder.SaveableStateProvider(categoryName){LazyColumn(state=rememberLazyListState(),contentPadding=PaddingValues(horizontal=16.dp)){
                items(visible,key={it.id}){s->Column{
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).padding(vertical=6.dp)){Text(s.name,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${s.category.label} · ${if(s.engine=="demo")"内置示例"else if(s.engine=="maccms")"API 规则"else if(s.engine=="kazumi")"Kazumi 规则"else"网页规则"}"+(if(testing==s.id)" · 测试中"else if(JSONObject(s.rules).optBoolean("knownAds"))" · 有广告记录"else""),fontSize=10.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        Box(Modifier.size(52.dp,48.dp).toggleable(s.enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={model.toggle(s)}).semantics{contentDescription="${s.name}启停"},contentAlignment=Alignment.Center){Switch(checked=s.enabled,onCheckedChange=null,modifier=Modifier.scale(.8f),colors=SwitchDefaults.colors(uncheckedTrackColor=MaterialTheme.colorScheme.surfaceVariant,uncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent))}
                        Box{IconButton(onClick={more=s.id}){Icon(Icons.Outlined.MoreVert,"${s.name}更多",Modifier.size(20.dp))};DropdownMenu(more==s.id,{more=null}){
                            if(s.engine!="demo")DropdownMenuItem(text={Text("测试连接")},enabled=testing==null,onClick={more=null;testing=s.id;scope.launch{try{val result=runCatching{model.repo.searchOne(s,if(s.engine in setOf("html","kazumi"))"海贼王"else"")}.fold({"${s.name}：返回 ${it.size} 项"},{ContentRepository.friendlyError(it)});notify(result)}finally{testing=null}}})
                            DropdownMenuItem(text={Text("规则信息")},onClick={more=null;info=s})
                            if(s.kind==Kind.VIDEO&&s.engine!="demo")DropdownMenuItem(text={Text("设置分类")},onClick={more=null;changing=s})
                            if(s.engine!="demo")DropdownMenuItem(text={Text("移除规则")},onClick={more=null;deleting=s})
                        }}
                    };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                }}
                if(visible.isEmpty())item{Text("此分类没有规则",Modifier.padding(vertical=24.dp),fontSize=14.sp)}
            }}
        }
    }
    info?.let{s->AlertDialog(onDismissRequest={info=null},title={Text(s.name)},text={Column{Text("${s.category.label} · ${s.engine}");Text(s.url.ifBlank{"内置离线示例"},fontSize=12.sp);if(JSONObject(s.rules).optBoolean("knownAds"))Text("此源有贴片广告记录，可保持关闭。",fontSize=12.sp)}},confirmButton={TextButton(onClick={info=null}){Text("关闭")}})}
    changing?.let{s->AlertDialog(onDismissRequest={changing=null},title={Text("设置分类")},text={Column{listOf(ContentCategory.VIDEO,ContentCategory.ANIME).forEach{c->TextButton(onClick={model.classify(s,c);changing=null}){Text(c.label)}}}},confirmButton={TextButton(onClick={changing=null}){Text("取消")}})}
    deleting?.let{s->AlertDialog(onDismissRequest={deleting=null},title={Text("移除内容源？")},text={Text("移除「${s.name}」后，收藏和阅读进度保留。")},confirmButton={TextButton(onClick={model.remove(s);deleting=null}){Text("移除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RuleImportSheet(model:AppModel,onClose:()->Unit,notify:(String)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var mode by rememberSaveable{mutableIntStateOf(0)};var input by rememberSaveable{mutableStateOf("")};var auto by rememberSaveable{mutableStateOf(true)};var busy by remember{mutableStateOf(false)};var issue by remember{mutableStateOf<String?>(null)}
    fun importText(text:String){runCatching{model.import(text)}.fold({notify("已导入 $it 个内容源"+(if(model.lastImportDisabledAds>0)"，有广告记录的规则默认关闭"else""));onClose()},{issue=ContentRepository.friendlyError(it)})}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)runCatching{context.contentResolver.openInputStream(uri)?.use{String(it.readBytesLimited(1024*1024),Charsets.UTF_8)}?:error("无法读取文件")}.fold({importText(it)},{issue=ContentRepository.friendlyError(it)})}
    Dialog(onDismissRequest={if(!busy)onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
            TopAppBar(title={Text("添加内容源",fontSize=18.sp)},navigationIcon={IconButton(enabled=!busy,onClick=onClose){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭导入")}},windowInsets=WindowInsets(0,0,0,0))
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("文件","订阅链接","粘贴规则").forEachIndexed{i,label->FilterChip(selected=mode==i,onClick={mode=i;issue=null},label={Text(label)},enabled=!busy)}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)){
                when(mode){
                    0->{Text("导入 JSON 规则文件",fontSize=18.sp);Text("支持拾光、Ficket、Kazumi 规则。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp));OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/json","text/*","application/octet-stream"))}){Icon(Icons.Outlined.FolderOpen,null);Text("从文件选择")}}
                    1->{OutlinedTextField(value=input,onValueChange={input=it;issue=null},singleLine=true,label={Text("规则订阅地址")},placeholder={Text("https://…/sources.json")},modifier=Modifier.fillMaxWidth(),enabled=!busy);Text("支持规则 JSON、Kazumi index.json。",fontSize=12.sp,modifier=Modifier.padding(vertical=12.dp));Row(verticalAlignment=Alignment.CenterVertically){Text("自动检查更新",Modifier.weight(1f));Switch(auto,onCheckedChange={auto=it},enabled=!busy)};Text("更新失败时继续使用已有规则。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    else->{OutlinedTextField(value=input,onValueChange={input=it;issue=null},label={Text("视频 API 地址或 JSON 规则")},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=320.dp),enabled=!busy)}
                }
                issue?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.padding(top=16.dp))}
            }
            if(mode!=0)Button(enabled=!busy&&input.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(16.dp).height(48.dp),onClick={
                if(mode==2)importText(input) else {busy=true;scope.launch{try{val message=model.subscribe(input);if(message.startsWith("刷新失败"))issue=message else {if(!auto){val id=SourceCodec.stableId("subscription|${input.trim()}");model.subscriptions.firstOrNull{it.id==id}?.takeIf{it.enabled}?.let{model.toggleSubscription(it)}};notify(message);onClose()}}catch(e:CancellationException){throw e}catch(e:Exception){issue=ContentRepository.friendlyError(e)}finally{busy=false}}}
            }){Text(if(busy)"正在读取…"else if(mode==1)"添加订阅"else"导入")}
        }}
    }
}
