package app.shiguang.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Ink=Color(0xFF1C302B)
private val Green=Color(0xFF285E50)
private val Paper=Color(0xFFF7F8F4)
private val Muted=Color(0xFF758078)
private val Cream=Color(0xFFE9EEE5)

@Composable fun ShiguangTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Green,onPrimary=Color.White,background=Paper,surface=Paper,
        onBackground=Ink,onSurface=Ink,secondary=Color(0xFFA46D37),surfaceVariant=Cream,onSurfaceVariant=Muted,
        primaryContainer=Cream,onPrimaryContainer=Green,secondaryContainer=Cream,onSecondaryContainer=Green),content=content)
}
class MainActivity: ComponentActivity() {
    var readerVolumeHandler:((Boolean)->Unit)?=null
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean {
        val handler=readerVolumeHandler
        if(handler!=null&&keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP)){
            if(event.repeatCount==0)handler(keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN);return true
        };return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode:Int,event:android.view.KeyEvent):Boolean {
        if(readerVolumeHandler!=null&&keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP))return true
        return super.onKeyUp(keyCode,event)
    }
    override fun onResume(){super.onResume();ResolverWindow.activity=java.lang.ref.WeakReference(this)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent { ShiguangTheme { ShiguangApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShiguangApp(model: AppModel=viewModel()) {
    val appContext=LocalContext.current
    val readerNavigation=remember{ReaderNavigationCache(appContext)}
    val browseState=rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableStateOf(0) }
    var selected by rememberSaveable(stateSaver=Saver<Entry?,String>(save={it?.json()?.toString() ?: ""},restore={if(it.isBlank())null else Entry.parse(JSONObject(it))})) { mutableStateOf<Entry?>(null) }
    var reader by rememberSaveable(stateSaver=Saver<Triple<Detail,Int,Int>?,String>(save={readerNavigation.save(it)},restore={readerNavigation.restore(it)})) { mutableStateOf<Triple<Detail,Int,Int>?>(null) }
    var narrationPlayer by rememberSaveable{mutableStateOf(false)}
    var openListening by rememberSaveable{mutableStateOf(false)}
    var importing by remember { mutableStateOf(false) }
    var direct by remember {mutableStateOf(false)}
    var storage by remember{mutableStateOf((appContext as? android.app.Activity)?.intent?.getBooleanExtra("openDownloads",false)==true)}
    val snack=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val playback by PlaybackSession.state.collectAsState()
    val narration by Narration.state.collectAsState()
    LaunchedEffect(narration.entry){if(narration.entry==null)narrationPlayer=false}
    if(narrationPlayer&&narration.entry!=null)NarrationPlayer(onClose={narrationPlayer=false},onCatalog={narrationPlayer=false;selected=narration.entry})
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME&&!model.libraryReady) model.refreshLibrary() }
        lifecycle.lifecycle.addObserver(observer);onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(selected!=null || reader!=null) { if(reader!=null) {reader=null;model.refreshLibrary()} else selected=null }
    if(reader?.first?.entry?.kind==Kind.NOVEL){val r=reader!!;NovelReaderScreen(model,r.first,r.second,r.third,onBack={reader=null;model.refreshLibrary()},startListening=openListening);return}
    if(reader?.first?.entry?.kind==Kind.COMIC){val r=reader!!;ComicReaderScreen(model,r.first,r.second,r.third,onBack={reader=null;model.refreshLibrary()});return}
    Scaffold(
        containerColor=Paper,
        snackbarHost={ SnackbarHost(snack) },
        bottomBar={Column {
            if(narration.entry!=null&&reader==null)Surface(color=Cream,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(horizontal=18.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Headphones,null,tint=Green);Column(Modifier.weight(1f).clickable{narrationPlayer=true}.padding(horizontal=12.dp)){Text(narration.entry!!.title,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(narration.message,fontSize=10.sp,color=Muted,maxLines=1)};IconButton(onClick={Narration.command(appContext,if(narration.active)"pause" else "play")}){Icon(if(narration.active)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(narration.active)"暂停听书" else "继续听书")};IconButton(onClick={Narration.command(appContext,"stop")}){Icon(Icons.Outlined.Close,"结束听书")}}}
            if(playback.entry!=null&&reader==null)Surface(color=Cream,modifier=Modifier.fillMaxWidth().clickable{appContext.startActivity(Intent(appContext,PlayerActivity::class.java).putExtra("resume",true))}) {
                Row(Modifier.padding(horizontal=22.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.PlayCircle,null,tint=Green);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(playback.entry!!.title,fontSize=13.sp,maxLines=1);Text(if(playback.playing)"正在播放 · 点击返回" else "返回播放器",fontSize=10.sp,color=Muted)};Icon(Icons.Outlined.ChevronRight,null,tint=Green)}
            }
            if(selected==null && reader==null) NavigationBar(containerColor=Paper,tonalElevation=0.dp,modifier=Modifier.navigationBarsPadding().height(64.dp),windowInsets=WindowInsets(0,0,0,0)) {
            listOf("发现" to Icons.Outlined.Explore,"书架" to Icons.Outlined.Bookmarks,"内容源" to Icons.Outlined.Layers).forEachIndexed { i,(label,icon) ->
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Cream))
            }
        } } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            Box(Modifier.widthIn(max=900.dp).fillMaxSize()) {
                when {
                    reader!=null -> {
                        val r=reader!!;ComicReaderScreen(model,r.first,r.second,r.third,onBack={reader=null;model.refreshLibrary()})
                    }
                    selected?.kind==Kind.NOVEL -> NovelDetailScreen(model,selected!!,onBack={selected=null},onRead={d,l,c->openListening=false;reader=Triple(d,l,c)},onListen={d,l,c->openListening=true;reader=Triple(d,l,c)},onSwitched={selected=it})
                    selected!=null -> DetailScreen(model,selected!!,onBack={selected=null},onRead={d,l,c->reader=Triple(d,l,c)},onSwitched={selected=it})
                    else -> browseState.SaveableStateProvider(tab){when(tab){
                        0->CompactDiscoverScreen(model,onOpen={selected=it.copy(category=model.entryCategory(it))},onAddRules={tab=2;importing=true},onDirect={direct=true},onStorage={storage=true})
                        1->CompactLibraryScreen(model,onOpen={selected=it.copy(category=model.entryCategory(it))})
                        else->CompactSourcesScreen(model,onImport={importing=true},notify={scope.launch{snack.showSnackbar(it)}})
                    }}
                }
            }
        }
    }
    if(storage)StorageSheet{storage=false}
    if(importing) RuleImportSheet(model,onClose={importing=false},notify={scope.launch {snack.showSnackbar(it)}})
    if(direct) {
        var url by remember {mutableStateOf("")};var issue by remember {mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={direct=false},title={Text("打开视频链接")},text={Column{
            Text("可粘贴 MP4、M3U8 直链或网页播放地址。网页会尝试提取视频，部分站点可能不兼容。",fontSize=13.sp,color=Muted)
            OutlinedTextField(value=url,onValueChange={url=it;issue=null},label={Text("视频地址")},modifier=Modifier.fillMaxWidth().padding(top=12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
            issue?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }},confirmButton={TextButton(onClick={runCatching {SourceCodec.httpUrl(url)}.fold({selected=Entry(SourceCodec.stableId(it),"direct-video","我的视频",Kind.VIDEO,it,subtitle="视频链接");direct=false},{issue=it.message})}){Text("打开")}},dismissButton={TextButton(onClick={direct=false}){Text("取消")}})
    }
}

private fun kindIcon(kind:Kind)=when(kind){Kind.VIDEO->Icons.Outlined.PlayCircle;Kind.COMIC->Icons.Outlined.AutoStories;Kind.NOVEL->Icons.AutoMirrored.Outlined.MenuBook}
@Composable internal fun Cover(e: Entry,modifier: Modifier=Modifier) {
    val colors=when(e.kind) {Kind.VIDEO->listOf(Color(0xFF315B4B),Color(0xFF182F2B));Kind.COMIC->listOf(Color(0xFFD2A46C),Color(0xFF875C45));Kind.NOVEL->listOf(Color(0xFF899BA5),Color(0xFF3D5866))}
    BoxWithConstraints(modifier.clip(RoundedCornerShape(if(e.kind==Kind.NOVEL)3.dp else 18.dp)).background(Brush.linearGradient(colors)),contentAlignment=Alignment.Center) {
        val compact=maxWidth<118.dp
        val compactNovel=e.kind==Kind.NOVEL&&compact
        if(e.cover.isNotBlank()) AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(e.cover).scale(coil.size.Scale.FIT).build(),contentDescription=e.title,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        else if(compact)Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=30.dp),verticalArrangement=Arrangement.Center){Text(e.title,color=Color.White,fontSize=14.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=3,overflow=TextOverflow.Ellipsis)}
        else if(e.kind==Kind.NOVEL)Column(Modifier.fillMaxSize().padding(if(compactNovel)PaddingValues(start=10.dp,top=12.dp,end=10.dp,bottom=32.dp)else PaddingValues(horizontal=16.dp,vertical=30.dp)),verticalArrangement=Arrangement.Center){
            Text(e.title,color=Color.White,fontSize=if(compactNovel)15.sp else 19.sp,fontWeight=FontWeight.Medium,lineHeight=if(compactNovel)21.sp else 26.sp,maxLines=if(compactNovel)3 else 4,overflow=TextOverflow.Ellipsis)
            if(!compactNovel){Spacer(Modifier.height(12.dp));Text("拾光书籍",color=Color.White.copy(.55f),fontSize=9.sp)}
        }
        else Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(kindIcon(e.kind),null,tint=Color.White.copy(.55f),modifier=Modifier.size(40.dp));Spacer(Modifier.height(14.dp))
            Text(e.title,color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold,lineHeight=31.sp)
            Spacer(Modifier.height(12.dp));Text(if(e.sourceId.startsWith("demo")) "SHIGUANG / 示例" else e.kind.label,color=Color.White.copy(.55f),fontSize=8.sp,letterSpacing=1.sp)
        }
        Box(Modifier.align(if(compactNovel)Alignment.BottomStart else Alignment.TopStart).padding(if(compact)6.dp else 10.dp).background(Color.Black.copy(.23f),RoundedCornerShape(6.dp)).padding(horizontal=7.dp,vertical=3.dp)) {
            Text(if(e.sourceId.startsWith("demo")) "示例" else e.category.label,color=Color.White,fontSize=if(compact)8.sp else 10.sp)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DetailScreen(model: AppModel,e: Entry,onBack:()->Unit,onRead:(Detail,Int,Int)->Unit,onSwitched:(Entry)->Unit={}) {
    var detail by remember(e.key) {mutableStateOf<Detail?>(null)}
    var failure by remember(e.key) {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    var lineIndex by remember(e.key) {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope();val context=LocalContext.current
    val sortPrefs=remember{context.getSharedPreferences("chapter-order",0)}
    var reverse by remember{mutableStateOf(sortPrefs.getBoolean(e.kind.name,false))}
    val source=model.source(e.sourceId)
    LaunchedEffect(e.key,retry) {
        detail=null;failure=null
        try {
            while(!model.libraryReady&&model.libraryError.isBlank())kotlinx.coroutines.delay(10)
            val source=model.source(e.sourceId)
            if(source==null) error("原内容源已移除，请重新导入后再打开")
            val loaded=model.repo.detail(source,e,force=retry>0)
            lineIndex=(model.history.find{it.entry.key==e.key}?.line ?: JSONObject(source.rules).optInt("preferredLine",0)).coerceIn(loaded.lines.indices)
            detail=loaded
            model.recordDetail(loaded,lineIndex)
        }
        catch(t:CancellationException){throw t}catch(t:Exception){failure=ContentRepository.friendlyError(t)}
    }
    fun start(d:Detail,l:Int,c:Int) {
        if(busy)return
        if(e.kind!=Kind.VIDEO) {onRead(d,l,c);return}
        busy=true;failure=null
        scope.launch {
            try {
                val chapter=d.lines[l].chapters[c]
                val playSource=model.source(d.entry.sourceId)?:error("目标内容源已移除")
                val url=chapter.url
                val progress=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){model.store.progressFor(d.entry.key)}?.takeIf{it.line==l&&it.chapter==c}
                val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",d.entry.json().toString()).putExtra("url",url)
                    .putExtra("line",l).putExtra("chapter",c).putExtra("offset",progress?.offset ?: 0L)
                    .putExtra("headers",JSONObject(model.repo.headers(playSource)).toString()).putExtra("original",chapter.url)
                    .apply{if(playSource.engine in setOf("html","kazumi","maccms","ficket"))putExtra("source",playSource.json().toString())}
                    .putExtra("knownAdSource",JSONObject(playSource.rules).optBoolean("knownAds"))
                    .putExtra("playlist",JSONArray().apply { d.lines[l].chapters.forEach {put(JSONObject().put("title",it.title).put("url",it.url))} }.toString())
                context.startActivity(intent)
            }catch(t:CancellationException){throw t}catch(t:Exception){failure=ContentRepository.friendlyError(t)}finally{busy=false}
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("内容详情",fontSize=16.sp)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},
            actions={FavoriteButton(model.favorites.any{it.key==e.key}){model.favorite(detail?.entry ?: e,detail)}})
        LazyColumn(contentPadding=PaddingValues(24.dp,0.dp,24.dp,28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Cover(e,Modifier.width(120.dp).height(164.dp))
                Column(Modifier.weight(1f)) {Spacer(Modifier.height(8.dp));Text(e.kind.label,color=Green,fontSize=12.sp);Spacer(Modifier.height(8.dp));Text(e.title,fontSize=24.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(12.dp));Text(source?.name ?: "源已移除",color=Muted,fontSize=12.sp);Text(e.subtitle,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))}
            }}
            item {Text((detail?.entry?.description ?: e.description).ifBlank {"此源暂无内容简介"},color=Muted,fontSize=14.sp,lineHeight=23.sp)}
            item {SourceSwitchButton(model,e,detail,onSwitched,onContinue={start(it.detail,it.line,it.chapter)})}
            if(failure!=null) item {Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF0DF))) {Column(Modifier.padding(16.dp)) {
                Text(failure!!,fontSize=13.sp);Row {TextButton(onClick={retry++}){Text("重新加载")};
                    if(ContentRepository.isHttp(e.url)) TextButton(onClick={openBrowser(context,e.url)}) {Text("浏览器打开")}}
            }}}
            if(detail==null && failure==null) item {Box(Modifier.fillMaxWidth().padding(30.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
            detail?.let { d ->
                val previous=model.history.find {it.entry.key==e.key}
                item {Button(onClick={val l=lineIndex.coerceIn(d.lines.indices);val c=(if(previous?.line==l)previous.chapter else 0).coerceIn(d.lines[l].chapters.indices);start(d,l,c)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)) {
                    Icon(if(e.kind==Kind.VIDEO) Icons.Outlined.PlayArrow else Icons.AutoMirrored.Outlined.MenuBook,null);Spacer(Modifier.width(8.dp));Text(if(busy) "准备播放…" else if(previous!=null) "继续${if(e.kind==Kind.VIDEO) "播放" else "阅读"}" else if(e.kind==Kind.VIDEO) "开始播放" else "开始阅读")
                }}
                if(d.lines.size>1) item {ScrollableTabRow(selectedTabIndex=lineIndex,containerColor=Paper,edgePadding=0.dp) {d.lines.forEachIndexed { i,l->Tab(selected=i==lineIndex,onClick={lineIndex=i},text={Text(l.name)})}}}
                item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${if(e.kind==Kind.VIDEO) "选集" else "目录"} · ${d.lines[lineIndex].chapters.size}",fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));ChapterSortButton(reverse){reverse=!reverse;sortPrefs.edit().putBoolean(e.kind.name,reverse).apply()}}}
                items(chapterDisplayIndices(d.lines[lineIndex].chapters.size,reverse),key={it}) { i ->
                    val ch=d.lines[lineIndex].chapters[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(previous?.chapter==i && previous.line==lineIndex) Cream else Color.White).clickable(enabled=!busy){start(d,lineIndex,i)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("%02d".format(i+1),color=Muted,fontSize=12.sp,modifier=Modifier.width(36.dp));Text(ch.title,modifier=Modifier.weight(1f),fontSize=14.sp);Icon(Icons.Outlined.ChevronRight,null,tint=Green,modifier=Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val count=read(buffer);if(count<0)break;require(out.size()+count<=limit){"文件超过大小限制"};out.write(buffer,0,count)}
    return out.toByteArray()
}
fun openBrowser(context:android.content.Context,url:String) {
    if(!ContentRepository.isHttp(url))return
    runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
}
