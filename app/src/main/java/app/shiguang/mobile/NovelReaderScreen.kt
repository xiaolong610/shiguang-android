package app.shiguang.mobile

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

private val ReadingBlue=Color(0xFF3975C6)
private val ReadingThemes=listOf(Color(0xFFF9F8F4) to Color(0xFF292723),Color(0xFFF1E6CE) to Color(0xFF40382B),Color(0xFFE3EBDD) to Color(0xFF344331),Color(0xFF1C1D20) to Color(0xFFBEBBB3))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NovelReaderScreen(model:AppModel,detail:Detail,line:Int,initial:Int,onBack:()->Unit,startListening:Boolean=false){
    val context=LocalContext.current;val entry=detail.entry;val chapters=detail.lines[line].chapters
    val preferences=remember{NovelPreferences(context)};val source=model.source(entry.sourceId)
    var index by rememberSaveable(entry.key){mutableIntStateOf(initial.coerceIn(chapters.indices))}
    var page by remember(entry.key){mutableStateOf<PageContent?>(null)};var failure by remember{mutableStateOf<String?>(null)};var retry by remember(index){mutableIntStateOf(0)}
    var menu by rememberSaveable{mutableStateOf(false)};var sheet by remember{mutableStateOf("")}
    var font by remember{mutableFloatStateOf(preferences.font)};var spacing by remember{mutableFloatStateOf(preferences.spacing)};var theme by remember{mutableIntStateOf(preferences.theme)};var serif by remember{mutableStateOf(preferences.serif)};var margin by remember{mutableIntStateOf(preferences.margin)};var keep by remember{mutableStateOf(preferences.keepScreen)}
    var brightness by remember{mutableFloatStateOf(-1f)};var marks by remember{mutableStateOf(preferences.marks(entry.key))}
    var jumpParagraph by remember{mutableStateOf<Int?>(null)}
    var turnMode by remember{mutableStateOf(preferences.turnMode)}
    var pagedInitialItem by remember(index){mutableIntStateOf(preferences.position(entry.key,index).first)}
    var pagedInitialOffset by remember(index){mutableIntStateOf(preferences.position(entry.key,index).second)}
    var pagedItem by remember(index){mutableIntStateOf(pagedInitialItem)};var pagedOffset by remember(index){mutableIntStateOf(pagedInitialOffset)}
    var pageNumber by remember(index){mutableIntStateOf(1)};var pageCount by remember(index){mutableIntStateOf(1)}
    var pagedJump by remember{mutableStateOf<Int?>(null)};var pagedRequest by remember{mutableIntStateOf(0)}
    val orderPrefs=remember{context.getSharedPreferences("chapter-order",0)};var reverse by remember{mutableStateOf(orderPrefs.getBoolean(Kind.NOVEL.name,false))}
    val scroll=rememberLazyListState();val scope=rememberCoroutineScope();val speech by Narration.state.collectAsState()
    val matching=speech.entry?.key==entry.key;val paragraphs=remember(page){NovelText.paragraphs(page?.text.orEmpty())}
    val (bg,fg)=ReadingThemes[theme];val muted=fg.copy(alpha=.48f)
    val visible=((if(turnMode==NovelTurnMode.SCROLL)scroll.firstVisibleItemIndex else pagedItem)-1).coerceIn(0,(paragraphs.size-1).coerceAtLeast(0))
    val chapterPercent by remember(scroll,paragraphs.size,page){derivedStateOf{
        if(turnMode!=NovelTurnMode.SCROLL)return@derivedStateOf (pageNumber*100/pageCount.coerceAtLeast(1)).coerceIn(0,100)
        val firstSize=scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1)?:1
        if(page!=null&&scroll.layoutInfo.totalItemsCount>1&&!scroll.canScrollForward)100 else (((scroll.firstVisibleItemIndex-1).coerceAtLeast(0)+if(scroll.firstVisibleItemIndex>0)scroll.firstVisibleItemScrollOffset.toFloat()/firstSize else 0f)*100/paragraphs.size.coerceAtLeast(1)).toInt().coerceIn(0,99)
    }}
    fun save(){if(page!=null){val item=if(turnMode==NovelTurnMode.SCROLL)scroll.firstVisibleItemIndex else pagedItem;val offset=if(turnMode==NovelTurnMode.SCROLL)scroll.firstVisibleItemScrollOffset else pagedOffset;preferences.position(entry.key,index,item,offset);model.store.save(entry,line,index,item.toLong(),chapters[index].title)}}
    fun selectTurnMode(value:NovelTurnMode){if(value==turnMode)return;save();if(turnMode==NovelTurnMode.SCROLL){pagedInitialItem=scroll.firstVisibleItemIndex;pagedInitialOffset=scroll.firstVisibleItemScrollOffset;pagedItem=pagedInitialItem;pagedOffset=pagedInitialOffset}else if(value==NovelTurnMode.SCROLL)scope.launch{withFrameNanos{};scroll.scrollToItem(pagedItem,pagedOffset)};turnMode=value;preferences.turnMode=value}
    fun change(next:Int,paragraph:Int?=null){if(next !in chapters.indices)return;save();if(next==index){if(paragraph!=null){if(turnMode==NovelTurnMode.SCROLL)scope.launch{scroll.scrollToItem(paragraph+1)}else{pagedJump=paragraph;pagedRequest++}};return};if(matching&&speech.active)Narration.command(context,"pause");page=null;pagedJump=null;jumpParagraph=paragraph;index=next}
    fun leave(){save();onBack()}
    fun listen(mode:String="system"){val body=page?:return;val s=source?:return;runCatching{Narration.start(context,s,detail,line,index,body.text,NovelText.paragraphOffset(body.text,visible),mode=mode)}.onFailure{failure="无法开始听书：${it.message}"};sheet="listen"}
    var listenRequested by rememberSaveable{mutableStateOf(startListening)}
    LaunchedEffect(page){if(page!=null&&listenRequested){sheet="listen";listenRequested=false}}
    BackHandler{if(sheet.isNotBlank())sheet=""else leave()}
    LaunchedEffect(index,retry){page=null;failure=null
        try{val loaded=model.repo.readingPage(source?:error("原内容源已移除"),entry,chapters[index],force=retry>0);page=loaded;val pos=preferences.position(entry.key,index);val item=jumpParagraph?.plus(1)?:pos.first;jumpParagraph=null;pagedInitialItem=item;pagedInitialOffset=if(item==pos.first)pos.second else 0;pagedItem=item;pagedOffset=pagedInitialOffset;withFrameNanos{};if(turnMode==NovelTurnMode.SCROLL)scroll.scrollToItem(item.coerceIn(0,NovelText.paragraphs(loaded.text).size),if(item==pos.first)pos.second else 0);model.store.save(entry,line,index,(if(turnMode==NovelTurnMode.SCROLL)scroll.firstVisibleItemIndex else pagedItem).toLong(),chapters[index].title)}
        catch(e:CancellationException){throw e}catch(e:Exception){failure=ContentRepository.friendlyError(e)}
    }
    LaunchedEffect(index,page){if(page!=null&&source!=null&&index<chapters.lastIndex&&ReadingPreload.allowed(context,source)){
        try{model.repo.readingPage(source,entry,chapters[index+1])}catch(e:CancellationException){throw e}catch(_:Exception){}
    }}
    LaunchedEffect(index,page,turnMode){if(page!=null&&turnMode==NovelTurnMode.SCROLL)snapshotFlow{scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset}.distinctUntilChanged().collect{(item,offset)->preferences.position(entry.key,index,item,offset)}}
    val lifecycle=LocalLifecycleOwner.current
    val saveLatest by rememberUpdatedState(newValue={save()})
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)saveLatest()};lifecycle.lifecycle.addObserver(observer);onDispose{saveLatest();lifecycle.lifecycle.removeObserver(observer)}}
    DisposableEffect(keep,brightness){val window=(context as? Activity)?.window;val old=window?.attributes?.screenBrightness
        if(keep)window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.let{val a=it.attributes;a.screenBrightness=brightness;it.attributes=a}
        onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(old!=null)window.let{val a=it.attributes;a.screenBrightness=old;it.attributes=a}}
    }
    DisposableEffect(theme){val window=(context as? Activity)?.window;val controller=window?.let{androidx.core.view.WindowCompat.getInsetsController(it,it.decorView)};val oldStatus=controller?.isAppearanceLightStatusBars;val oldNav=controller?.isAppearanceLightNavigationBars;controller?.isAppearanceLightStatusBars=theme!=3;controller?.isAppearanceLightNavigationBars=theme!=3;onDispose{if(oldStatus!=null)controller.isAppearanceLightStatusBars=oldStatus;if(oldNav!=null)controller.isAppearanceLightNavigationBars=oldNav}}
    // Follow an active narration's chapter; keep ordinary reading independent while narration is paused.
    LaunchedEffect(speech.chapter,speech.offset,speech.playing){if(matching&&speech.playing){if(speech.chapter!=index){save();page=null;index=speech.chapter.coerceIn(chapters.indices)}else if(page!=null){val target=NovelText.paragraphAt(page!!.text,speech.offset)+1;if(turnMode==NovelTurnMode.SCROLL){if(target!=scroll.firstVisibleItemIndex)scroll.animateScrollToItem(target)}else{pagedJump=target-1;pagedRequest++}}}}
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=if(theme==3)Color(0xFF8AB6F0)else ReadingBlue,onPrimary=if(theme==3)Color(0xFF132F52)else Color.White,surface=bg,onSurface=fg,onSurfaceVariant=muted,surfaceVariant=bg)){
    Box(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()){
        Column(Modifier.fillMaxSize()){
            Text(entry.title,color=muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().height(52.dp).padding(horizontal=margin.dp,vertical=16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()){
            when{
                failure!=null->Column(Modifier.align(Alignment.Center).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(failure!!,color=fg);TextButton(onClick={retry++}){Text("重新加载")}}
                page==null->CircularProgressIndicator(Modifier.align(Alignment.Center),color=ReadingBlue)
                turnMode!=NovelTurnMode.SCROLL->PagedNovelBody(entry.key,index,chapters[index].title,paragraphs,turnMode,font,spacing,serif,margin,fg,pagedInitialItem,pagedInitialOffset,pagedJump,pagedRequest,
                    onPosition={item,offset,number,count->pagedItem=item;pagedOffset=offset;pageNumber=number;pageCount=count;if(page!=null)preferences.position(entry.key,index,item,offset)},onMenu={menu=!menu},onPreviousChapter={if(index>0)change(index-1,Int.MAX_VALUE-1)},onNextChapter={if(index<chapters.lastIndex)change(index+1)})
                else->LazyColumn(state=scroll,modifier=Modifier.fillMaxSize().pointerInput(entry.key,index){detectTapGestures(onTap={menu=!menu})},contentPadding=PaddingValues(horizontal=margin.dp)){
                    item(key="heading"){Text(chapters[index].title,color=fg,fontSize=(font+7).sp,fontWeight=FontWeight.Medium,lineHeight=(font+18).sp,modifier=Modifier.fillMaxWidth().padding(top=34.dp,bottom=32.dp).pointerInput(Unit){detectTapGestures(onTap={menu=!menu})})}
                    itemsIndexed(paragraphs,key={i,_->"p$i"}){i,text->
                        val speaking=matching&&speech.playing&&speech.chapter==index&&NovelText.paragraphAt(page!!.text,speech.offset)==i
                        SelectionContainer{Text("　　$text",color=if(speaking)ReadingBlue else fg,fontSize=font.sp,lineHeight=(font*spacing).sp,fontFamily=if(serif)FontFamily.Serif else FontFamily.Default,textAlign=TextAlign.Justify,modifier=Modifier.fillMaxWidth().padding(bottom=(font*.8f).dp))}
                    }
                    item(key="end"){Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("· 本章完 ·",fontSize=12.sp,color=muted);Spacer(Modifier.height(22.dp));if(index<chapters.lastIndex)TextButton(onClick={change(index+1)}){Text("下一章  ${chapters[index+1].title}",color=ReadingBlue)}else Text("已读至全书结尾",color=muted,fontSize=13.sp)}}
                }
            }
        }
            Row(Modifier.fillMaxWidth().heightIn(min=28.dp).padding(horizontal=margin.dp).clickable{menu=true}.semantics{contentDescription="唤出阅读操作"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(chapters[index].title,color=muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));Text(if(turnMode==NovelTurnMode.SCROLL)"${index+1} / ${chapters.size} · 本章 $chapterPercent%"else"${index+1} / ${chapters.size} · $pageNumber / $pageCount 页",color=muted,fontSize=10.sp)}
        }
        if(menu){
            Surface(color=bg,modifier=Modifier.align(Alignment.TopCenter).fillMaxWidth()){
                Row(Modifier.height(52.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={leave()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Text(entry.title,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));val marked=marks.any{it.chapter==index&&it.paragraph==visible};IconButton(onClick={preferences.toggleMark(entry.key,index,visible,paragraphs.getOrNull(visible).orEmpty());marks=preferences.marks(entry.key)}){Icon(if(marked)Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,"添加或移除书签")}}
            }
            Surface(color=bg,modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth()){
                Column(Modifier.padding(horizontal=16.dp)){
                    HorizontalDivider(color=fg.copy(alpha=.07f))
                    Row(Modifier.fillMaxWidth().clickable{sheet="progress"}.heightIn(min=32.dp).semantics{contentDescription="阅读进度"},horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(chapters[index].title,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));Text("${index+1} / ${chapters.size}",fontSize=10.sp,color=muted)}
                    var target by remember(index){mutableFloatStateOf(index.toFloat())}
                    if(chapters.size>1)Slider(value=target,onValueChange={target=it},onValueChangeFinished={change(target.toInt())},valueRange=0f..chapters.lastIndex.toFloat(),modifier=Modifier.height(32.dp).semantics{contentDescription="章节进度"})
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceEvenly){ReaderAction(Icons.Outlined.FormatListBulleted,"目录",fg){sheet="chapters"};ReaderAction(Icons.Outlined.Headphones,"听书",fg){sheet="listen"};ReaderAction(Icons.Outlined.TextFields,"设置",fg){sheet="settings"}}
                }
            }
        }
    }
    if(sheet.isNotEmpty())ModalBottomSheet(onDismissRequest={sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=bg,contentColor=fg){
        Column(Modifier.fillMaxWidth().heightIn(max=580.dp).navigationBarsPadding().padding(horizontal=24.dp).padding(bottom=28.dp)){
            when(sheet){
                "settings"->{Column(Modifier.verticalScroll(rememberScrollState())){
                    Text("阅读设置",fontSize=21.sp,fontWeight=FontWeight.Medium)
                    Text("翻页方式",fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){NovelTurnMode.entries.forEach{value->FilterChip(selected=turnMode==value,onClick={selectTurnMode(value)},label={Text(value.label,fontSize=11.sp)})}}
                    Text(if(turnMode==NovelTurnMode.SCROLL)"上下滑动连续阅读"else if(turnMode==NovelTurnMode.SLIDE)"左滑下一页、右滑上一页；点中间展开操作"else"点左侧上一页、右侧下一页；点中间展开操作",fontSize=11.sp,color=muted,modifier=Modifier.padding(bottom=8.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){Text("字号",Modifier.weight(1f));TextButton(onClick={font=(font-1).coerceAtLeast(16f);preferences.font=font}){Text("−")};Text(font.toInt().toString());TextButton(onClick={font=(font+1).coerceAtMost(32f);preferences.font=font}){Text("＋")}}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("行距",Modifier.weight(1f));listOf("紧凑" to 1.5f,"标准" to 1.9f,"宽松" to 2.3f).forEach{(label,value)->FilterChip(selected=kotlin.math.abs(spacing-value)<.05f,onClick={spacing=value;preferences.spacing=value},label={Text(label,fontSize=12.sp)},modifier=Modifier.padding(start=6.dp))}}
                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){ReadingThemes.forEachIndexed{i,p->Box(Modifier.size(46.dp).clip(CircleShape).background(p.first).border(if(i==theme)2.dp else 1.dp,if(i==theme)ReadingBlue else Color.Gray,CircleShape).clickable{theme=i;preferences.theme=i},contentAlignment=Alignment.Center){Text(listOf("白","纸","绿","夜")[i],color=p.second)}}}
                    Row(verticalAlignment=Alignment.CenterVertically){Text("亮度",fontSize=12.sp);Slider(value=if(brightness<0).5f else brightness,onValueChange={brightness=it},valueRange=.03f..1f,modifier=Modifier.weight(1f));TextButton(onClick={brightness=-1f}){Text("跟随系统",fontSize=11.sp)}}
                    TextButton(onClick={sheet="more-settings"},modifier=Modifier.fillMaxWidth()){Text("字体、边距、常亮 · 更多设置")}
                }}
                "more-settings"->{Column(Modifier.verticalScroll(rememberScrollState())){Text("更多阅读设置",fontSize=21.sp);Row(verticalAlignment=Alignment.CenterVertically){Text("宋体风格",Modifier.weight(1f));Switch(serif,onCheckedChange={serif=it;preferences.serif=it})};Row(verticalAlignment=Alignment.CenterVertically){Text("阅读时保持亮屏",Modifier.weight(1f));Switch(keep,onCheckedChange={keep=it;preferences.keepScreen=it})};Text("页边距",fontSize=12.sp);Slider(value=margin.toFloat(),onValueChange={margin=it.toInt();preferences.margin=margin},valueRange=16f..40f);TextButton(onClick={sheet="settings"}){Text("返回阅读设置")}}}
                "chapters"->{var bookmarks by remember{mutableStateOf(false)};Row(verticalAlignment=Alignment.CenterVertically){Text(entry.title,Modifier.weight(1f),fontSize=18.sp,maxLines=1);if(!bookmarks)ChapterSortButton(reverse){reverse=!reverse;orderPrefs.edit().putBoolean(Kind.NOVEL.name,reverse).apply()};TextButton(onClick={bookmarks=!bookmarks}){Text(if(bookmarks)"查看目录" else "书签 ${marks.size}")}};val directoryState=rememberLazyListState(initialFirstVisibleItemIndex=if(bookmarks)0 else if(reverse)chapters.lastIndex-index else index);LazyColumn(state=directoryState){if(bookmarks){if(marks.isEmpty())item{Text("阅读时点击右上角书签，留住这一页。",Modifier.padding(vertical=24.dp),color=muted)};items(marks,key={"${it.chapter}:${it.paragraph}"}){mark->Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).clickable{change(mark.chapter,mark.paragraph);sheet=""}){Text(chapters.getOrNull(mark.chapter)?.title?:"章节已变更",color=fg,fontSize=14.sp);Text(mark.snippet,maxLines=2,overflow=TextOverflow.Ellipsis,color=muted,fontSize=12.sp)};IconButton(onClick={preferences.toggleMark(entry.key,mark.chapter,mark.paragraph,mark.snippet);marks=preferences.marks(entry.key)}){Icon(Icons.Outlined.Close,"移除书签")}}}}else items(chapterDisplayIndices(chapters.size,reverse),key={it}){i->Text(chapters[i].title,color=if(i==index)ReadingBlue else fg,fontSize=14.sp,modifier=Modifier.fillMaxWidth().clickable{change(i);sheet=""}.padding(vertical=16.dp))}}}
                "progress"->{var target by remember(index){mutableFloatStateOf(index.toFloat())};Text("阅读进度",fontSize=21.sp);Spacer(Modifier.height(22.dp));Text(chapters[target.toInt().coerceIn(chapters.indices)].title,color=muted);if(chapters.size>1)Slider(value=target,onValueChange={target=it},onValueChangeFinished={change(target.toInt())},valueRange=0f..chapters.lastIndex.toFloat());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=index>0,onClick={change(index-1)}){Text("上一章")};TextButton(enabled=index<chapters.lastIndex,onClick={change(index+1)}){Text("下一章")}}}
                "listen"->{NarrationPanel(speech,matching,page!=null,onStart={listen(it)},onCatalog={sheet="chapters"})}
            }
        }
    }
}

}
@Composable private fun ReaderAction(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,color:Color,onClick:()->Unit){Column(Modifier.widthIn(min=56.dp).clickable(onClick=onClick).padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,label,tint=color,modifier=Modifier.size(23.dp));Text(label,fontSize=11.sp,color=color,modifier=Modifier.padding(top=7.dp))}}
