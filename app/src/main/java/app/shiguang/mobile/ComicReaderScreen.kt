package app.shiguang.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ComicReaderScreen(model:AppModel,detail:Detail,line:Int,initial:Int,onBack:()->Unit){
    val context=LocalContext.current;val entry=detail.entry;val chapters=detail.lines[line].chapters
    val screenHeight=LocalConfiguration.current.screenHeightDp.dp
    val source=model.source(entry.sourceId);val prefs=remember{context.getSharedPreferences("reader",0)}
    val sortPrefs=remember{context.getSharedPreferences("chapter-order",0)}
    var reverse by remember{mutableStateOf(sortPrefs.getBoolean(Kind.COMIC.name,false))}
    var mode by remember{mutableStateOf(ComicTurnMode.parse(prefs.getString("comicTurnMode",null)))}
    var night by remember{mutableStateOf(prefs.getBoolean("night",false))}
    var index by rememberSaveable(entry.key){mutableIntStateOf(initial.coerceIn(chapters.indices))}
    var page by remember{mutableStateOf<PageContent?>(null)};var failure by remember{mutableStateOf<String?>(null)}
    var retry by remember(index){mutableIntStateOf(0)};var menu by rememberSaveable{mutableStateOf(false)};var sheet by remember{mutableStateOf("")}
    var ready by remember{mutableStateOf(false)};var openAtEnd by remember{mutableStateOf(false)}
    val list=rememberLazyListState();val pager=rememberPagerState{(page?.images?.size?:0)+1};val scope=rememberCoroutineScope()
    val loader=remember{ReadingPreload.images(context)}
    val imageWidth=context.resources.displayMetrics.widthPixels
    val bg=if(night)Color(0xFF202825)else Color(0xFFFAF5E9);val fg=if(night)Color(0xFFD3DACE)else Color(0xFF1C302B)
    fun positionKey()="comic:${entry.key}:$line:$index"
    fun save(){if(page!=null&&ready){val image=if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemIndex else pager.currentPage;val offset=if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemScrollOffset else 0;prefs.edit().putString(positionKey(),"$image:$offset").apply();model.store.save(entry,line,index,image.toLong(),chapters[index].title)}}
    fun change(next:Int,atEnd:Boolean=false){if(next !in chapters.indices)return;save();ready=false;page=null;openAtEnd=atEnd;index=next}
    fun leave(){save();onBack()}
    fun turn(next:Boolean){if(scope.coroutineContext.isActive)scope.launch{val target=pager.currentPage+if(next)1 else -1;if(target<0){if(index>0)change(index-1,true)}else if(target>pager.pageCount-1){if(index<chapters.lastIndex)change(index+1)}else pager.animateScrollToPage(target)}}
    fun selectMode(value:ComicTurnMode){if(value==mode)return;save();val image=if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemIndex else pager.currentPage;mode=value;prefs.edit().putString("comicTurnMode",value.name).apply();scope.launch{if(value==ComicTurnMode.SCROLL)list.scrollToItem(image.coerceIn(0,page?.images?.size?:0))else pager.scrollToPage(image.coerceIn(0,pager.pageCount-1))}}
    BackHandler{if(sheet.isNotEmpty())sheet=""else leave()}
    LaunchedEffect(index,retry,source?.id){ready=false;page=null;failure=null
        try{val loaded=model.repo.readingPage(source?:error("原内容源已移除"),entry,chapters[index],force=retry>0);require(loaded.images.isNotEmpty()){"本话没有可显示的图片"};page=loaded
            val saved=prefs.getString(positionKey(),null)?.split(':');val image=if(openAtEnd)loaded.images.lastIndex else(saved?.getOrNull(0)?.toIntOrNull()?:0).coerceIn(0,loaded.images.size)
            withFrameNanos{};if(mode==ComicTurnMode.SCROLL){list.scrollToItem(image,if(openAtEnd)0 else saved?.getOrNull(1)?.toIntOrNull()?:0)
                if(saved==null&&!openAtEnd){val old=withContext(Dispatchers.IO){model.store.progressFor(entry.key)}?.takeIf{it.line==line&&it.chapter==index};if(old!=null&&old.offset>0)list.scrollBy(old.offset.toFloat())}
            }else pager.scrollToPage(image);openAtEnd=false;ready=true;save()
        }catch(e:CancellationException){throw e}catch(e:Exception){failure=ContentRepository.friendlyError(e)}
    }
    val visibleImage by remember{derivedStateOf{if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemIndex else pager.currentPage}}
    DisposableEffect(page,index,visibleImage,mode){
        val tasks=if(page!=null&&ReadingPreload.allowed(context,source))page!!.images.drop(visibleImage+1).take(3).map{url->loader.enqueue(ReadingPreload.image(context,model.repo,source,url,imageWidth))}else emptyList()
        onDispose{tasks.forEach{it.dispose()}}
    }
    LaunchedEffect(index,page){if(page!=null&&source!=null&&index<chapters.lastIndex&&ReadingPreload.allowed(context,source)){
        try{model.repo.readingPage(source,entry,chapters[index+1])}catch(e:CancellationException){throw e}catch(_:Exception){}
    }}
    LaunchedEffect(index,page,mode,ready){if(ready)snapshotFlow{if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset else pager.currentPage to 0}.distinctUntilChanged().collect{save()}}
    val latestSave by rememberUpdatedState(newValue={save()});val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)latestSave()};lifecycle.lifecycle.addObserver(observer);onDispose{latestSave();lifecycle.lifecycle.removeObserver(observer)}}
    @Composable fun image(i:Int,paged:Boolean){
        var imageRetry by remember(index,i){mutableIntStateOf(0)}
        key(imageRetry){SubcomposeAsyncImage(model=ReadingPreload.image(context,model.repo,source,page!!.images[i],imageWidth,retry=imageRetry>0),imageLoader=loader,
            contentDescription="第 ${i+1} 页",contentScale=if(paged)ContentScale.Fit else ContentScale.FillWidth,
            loading={Box(Modifier.fillMaxWidth().heightIn(min=180.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp))}},
            error={Column(Modifier.fillMaxWidth().heightIn(min=180.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("第 ${i+1} 页加载失败",color=fg);TextButton(onClick={imageRetry++}){Text("重新加载图片")}}},
            modifier=if(paged)Modifier.fillMaxSize()else Modifier.fillMaxWidth().heightIn(min=160.dp))}
    }
    @Composable fun end(){Column(Modifier.fillMaxWidth().heightIn(min=if(mode==ComicTurnMode.SCROLL)screenHeight else 0.dp).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("本话共 ${page?.images?.size?:0} 页",color=fg,fontSize=13.sp);TextButton(enabled=index<chapters.lastIndex,onClick={change(index+1)}){Text(if(index<chapters.lastIndex)"下一话"else"已读至最后一话")}}}
    Box(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()){
        when{
            failure!=null->Column(Modifier.align(Alignment.Center).padding(24.dp)){Text(failure!!,color=fg);TextButton(onClick={retry++}){Text("重试")}}
            page==null->CircularProgressIndicator(Modifier.align(Alignment.Center))
            mode==ComicTurnMode.SCROLL->LazyColumn(state=list,modifier=Modifier.fillMaxSize().semantics{contentDescription="漫画条漫阅读区"}.pointerInput(index){detectTapGestures(onTap={menu=!menu})}){items(page!!.images.size,key={it}){image(it,false)};item{end()}}
            else->HorizontalPager(state=pager,reverseLayout=mode==ComicTurnMode.RIGHT,modifier=Modifier.fillMaxSize().semantics{contentDescription="漫画分页阅读区"}){i->
                Box(Modifier.fillMaxSize().pointerInput(index,mode){detectTapGestures(onTap={p->when{p.x<size.width*.3f->turn(mode==ComicTurnMode.RIGHT);p.x>size.width*.7f->turn(mode!=ComicTurnMode.RIGHT);else->menu=!menu}})}){if(i<page!!.images.size)image(i,true)else Box(Modifier.align(Alignment.Center)){end()}}
            }
        }
        if(page!=null&&!menu){Text("${if(mode==ComicTurnMode.SCROLL)list.firstVisibleItemIndex.coerceAtMost(page!!.images.lastIndex)+1 else(pager.currentPage+1).coerceAtMost(page!!.images.size)} / ${page!!.images.size}",color=fg.copy(alpha=.65f),fontSize=10.sp,modifier=Modifier.align(Alignment.BottomEnd).background(bg.copy(alpha=.85f)).clickable{menu=true}.padding(8.dp).semantics{contentDescription="唤出漫画操作"})}
        if(menu){
            TopAppBar(title={Text(chapters[index].title,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick={leave()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},windowInsets=WindowInsets(0,0,0,0),colors=TopAppBarDefaults.topAppBarColors(containerColor=bg,titleContentColor=fg,navigationIconContentColor=fg),modifier=Modifier.align(Alignment.TopCenter))
            Surface(color=bg,modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth()){
                Row(Modifier.heightIn(min=52.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly){TextButton(enabled=index>0,onClick={change(index-1)}){Text("上一话")};TextButton(onClick={sheet="chapters"}){Text("目录")};TextButton(onClick={sheet="settings"}){Text("设置")};TextButton(enabled=index<chapters.lastIndex,onClick={change(index+1)}){Text("下一话")}}
            }
        }
    }
    if(sheet.isNotEmpty())ModalBottomSheet(onDismissRequest={sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=bg,contentColor=fg){
        if(sheet=="settings")Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp)){
            Text("漫画阅读设置",fontSize=21.sp);Spacer(Modifier.height(18.dp));Text("翻页方式",fontSize=14.sp)
            ComicTurnMode.entries.forEach{value->Row(Modifier.fillMaxWidth().clickable{selectMode(value)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=mode==value,onClick={selectMode(value)});Column{Text(value.label);Text(value.hint,fontSize=12.sp,color=fg.copy(alpha=.6f))}}}
            HorizontalDivider();Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("夜间配色",Modifier.weight(1f));Switch(night,onCheckedChange={night=it;prefs.edit().putBoolean("night",night).apply()})};Text("点页面中间可展开或收起操作栏",color=fg.copy(alpha=.6f),fontSize=12.sp)
        }else Column(Modifier.fillMaxWidth().heightIn(max=550.dp).navigationBarsPadding().padding(horizontal=20.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text("目录 · ${chapters.size} 话",Modifier.weight(1f));ChapterSortButton(reverse){reverse=!reverse;sortPrefs.edit().putBoolean(Kind.COMIC.name,reverse).apply()}}
            LazyColumn{items(chapterDisplayIndices(chapters.size,reverse),key={it}){i->Text(chapters[i].title,color=if(i==index)Color(0xFF3975C6)else fg,fontSize=14.sp,modifier=Modifier.fillMaxWidth().clickable{change(i);sheet=""}.padding(vertical=16.dp))}}
        }
    }
}
