package app.shiguang.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NovelDetailScreen(model:AppModel,entry:Entry,onBack:()->Unit,onRead:(Detail,Int,Int)->Unit,onListen:(Detail,Int,Int)->Unit=onRead,onSwitched:(Entry)->Unit={}){
    var detail by remember(entry.key){mutableStateOf<Detail?>(null)};var failure by remember{mutableStateOf<String?>(null)};var retry by remember{mutableIntStateOf(0)}
    var line by remember{mutableIntStateOf(0)}
    val context=LocalContext.current;val sortPrefs=remember{context.getSharedPreferences("chapter-order",0)}
    var reverse by remember{mutableStateOf(sortPrefs.getBoolean(Kind.NOVEL.name,false))}
    val blue=Color(0xFF3975C6);val muted=Color(0xFF888A8D);val paper=Color(0xFFFAFAF8)
    LaunchedEffect(entry.key,retry){failure=null;try{detail=model.repo.detail(model.source(entry.sourceId)?:error("原内容源已移除"),entry,force=retry>0);line=(model.history.find{it.entry.key==entry.key}?.line?:0).coerceIn(detail!!.lines.indices);model.recordDetail(detail!!,line)}catch(e:CancellationException){throw e}catch(e:Exception){failure=ContentRepository.friendlyError(e)}}
    val saved=model.favorites.any{it.key==entry.key}
    Column(Modifier.fillMaxSize().background(paper).navigationBarsPadding()){
        TopAppBar(title={Text("书籍",fontSize=16.sp)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},actions={FavoriteButton(saved){model.favorite(detail?.entry?:entry,detail)}},windowInsets=WindowInsets(0,0,0,0),colors=TopAppBarDefaults.topAppBarColors(containerColor=paper))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=26.dp,vertical=18.dp)){
            item{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Cover(entry,Modifier.size(126.dp,172.dp));Text(entry.title,fontSize=25.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=22.dp));Text(model.source(entry.sourceId)?.name?:"内容源已移除",fontSize=12.sp,color=muted,modifier=Modifier.padding(top=10.dp));Text(entry.subtitle,color=muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp,bottom=24.dp))}}
            item{Text("简介",fontSize=18.sp,fontWeight=FontWeight.Medium);Text((detail?.entry?.description?:entry.description).ifBlank{"暂无简介"},fontSize=14.sp,lineHeight=24.sp,color=muted,modifier=Modifier.padding(top=12.dp,bottom=28.dp))}
            item{SourceSwitchButton(model,entry,detail,onSwitched,onContinue={onRead(it.detail,it.line,it.chapter)})}
            if(detail==null&&failure==null)item{CircularProgressIndicator(Modifier.padding(24.dp),color=blue)}
            failure?.let{item{Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}){Text("重新加载")}}}
            detail?.let{d->
                if(d.lines.size>1)item{ScrollableTabRow(selectedTabIndex=line,containerColor=paper){d.lines.forEachIndexed{i,l->Tab(selected=i==line,onClick={line=i},text={Text(l.name)})}}}
                item{Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically){Text("目录",fontSize=19.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f));Text("${d.lines[line].chapters.size} 章",fontSize=12.sp,color=muted);ChapterSortButton(reverse){reverse=!reverse;sortPrefs.edit().putBoolean(Kind.NOVEL.name,reverse).apply()}}}
                items(chapterDisplayIndices(d.lines[line].chapters.size,reverse),key={it}){i->val ch=d.lines[line].chapters[i];Column(Modifier.fillMaxWidth().clickable{onRead(d,line,i)}){Text(ch.title,color=if(model.history.any{it.entry.key==entry.key&&it.line==line&&it.chapter==i})blue else Color(0xFF33363A),fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=19.dp));HorizontalDivider(color=Color(0xFFEEEEEB))}}
            }
        }
        Surface(color=paper,shadowElevation=2.dp){Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Button(enabled=detail!=null,onClick={detail?.let{d->val old=model.history.find{it.entry.key==entry.key&&it.line==line};onRead(d,line,(old?.chapter?:0).coerceIn(d.lines[line].chapters.indices))}},modifier=Modifier.weight(1f).height(50.dp),colors=ButtonDefaults.buttonColors(containerColor=blue)){Text(if(model.history.any{it.entry.key==entry.key})"继续阅读" else "开始阅读")}
            TextButton(enabled=detail!=null,onClick={detail?.let{d->val old=model.history.find{it.entry.key==entry.key&&it.line==line};onListen(d,line,(old?.chapter?:0).coerceIn(d.lines[line].chapters.indices))}},modifier=Modifier.height(50.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.Headphones,"听书",Modifier.size(22.dp));Text("听书",fontSize=10.sp)}}
        }}
    }
}
