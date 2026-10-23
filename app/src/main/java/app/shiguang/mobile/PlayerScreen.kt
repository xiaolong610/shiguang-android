package app.shiguang.mobile

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** Display indices can reverse; every click still sends the original playlist index. */
internal fun episodeOrder(size:Int,reversed:Boolean):List<Int> = (0 until size).toList().let{if(reversed)it.reversed()else it}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompactPlayerScreen(info:PlaybackInfo,player:Player?,landscape:Boolean,inPip:Boolean,background:Boolean,autoPip:Boolean,connectionError:String?,onBack:()->Unit,onPip:()->Unit,onFullscreen:()->Unit,onPick:(Int)->Unit,onFilter:()->Unit,onRetry:()->Unit,onBrowser:()->Unit,onStop:()->Unit,onBackground:(Boolean)->Unit,onAutoPip:(Boolean)->Unit,onBoost:(Boolean)->Unit={},onSources:()->Unit={},onDownload:()->Unit={}){
    val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("player",0)}
    var settings by rememberSaveable{mutableStateOf(false)};var reverse by rememberSaveable{mutableStateOf(prefs.getBoolean("episodeReverse",false))};val speed=info.speed;var controls by remember{mutableStateOf(true)}
    LaunchedEffect(inPip){if(inPip)settings=false}
    val fullscreen=landscape||inPip
    Column(Modifier.fillMaxSize().background(if(fullscreen)Color.Black else MaterialTheme.colorScheme.background).then(if(inPip)Modifier else Modifier.safeDrawingPadding())){
        if(!fullscreen)Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Text(info.entry?.title?:"准备播放",fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));IconButton(onClick={settings=true}){Icon(Icons.Outlined.Settings,"播放设置")}}
        val videoModifier=if(fullscreen)Modifier.fillMaxWidth().weight(1f)else Modifier.fillMaxWidth().aspectRatio(16f/9f)
        Box(videoModifier.background(Color.Black)){
            if(player==null)CircularProgressIndicator(Modifier.align(Alignment.Center))
            else AndroidView(factory={GesturePlayerView(it).apply{this.player=player;setShowSubtitleButton(true);setControllerVisibilityListener(PlayerView.ControllerVisibilityListener{visibility->controls=visibility==View.VISIBLE})}},update={it.player=player;it.useController=!inPip;it.gesturesEnabled=!inPip;it.onBoost=onBoost},onRelease={it.releaseBoost();it.player=null},modifier=Modifier.fillMaxSize())
            if(info.boosting&&!inPip)Text("长按 ${info.speed}× · 松手恢复",color=Color.White,fontSize=13.sp,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.55f)).padding(10.dp))
            if(landscape&&!inPip&&controls)IconButton(onClick=onBack,modifier=Modifier.align(Alignment.TopStart)){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回窗口",tint=Color.White)}
            if(!inPip&&controls)Row(Modifier.align(Alignment.TopEnd)){
                IconButton(onClick=onPip){Icon(Icons.Outlined.PictureInPictureAlt,"画中画",tint=Color.White)}
                IconButton(onClick=onFullscreen){Icon(if(landscape)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,if(landscape)"退出全屏"else"切换横竖屏",tint=Color.White)}
                if(landscape)IconButton(onClick={settings=true}){Icon(Icons.Outlined.Settings,"播放设置",tint=Color.White)}
            }
        }
        if(!fullscreen){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(info.entry?.title.orEmpty(),fontSize=19.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(info.title,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)};TextButton(onClick={settings=true}){Text("${speed}×")}}
            if(info.status.isNotBlank())Text(info.status,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
            (connectionError?:info.error)?.let{message->Column(Modifier.padding(horizontal=16.dp)){Text(message,color=MaterialTheme.colorScheme.error,fontSize=12.sp);Row{TextButton(onClick=onRetry){Text("重新解析")};TextButton(onClick=onBrowser){Text("浏览器打开")}}}}
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("选集 · ${info.total}",fontSize=17.sp,modifier=Modifier.weight(1f));TextButton(onClick={reverse=!reverse;prefs.edit().putBoolean("episodeReverse",reverse).apply()},modifier=Modifier.semantics{contentDescription="选集排序"}){Text(if(reverse)"倒序 ↓"else"正序 ↑")}}
            LazyVerticalGrid(columns=GridCells.Adaptive(76.dp),modifier=Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(16.dp,4.dp,16.dp,16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                items(episodeOrder(info.pages.size,reverse),key={it}){index->
                    val selected=index==info.chapter
                    Surface(onClick={onPick(index)},shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().heightIn(min=44.dp).semantics{contentDescription="播放第${index+1}集"}){
                        Box(Modifier.padding(horizontal=6.dp,vertical=8.dp),contentAlignment=Alignment.Center){Text(info.pages[index].title.ifBlank{"第 ${index+1} 集"},fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)}
                    }
                }
            }
        }else if(!inPip)(connectionError?:info.error)?.let{Text(it,color=Color.White,fontSize=12.sp,modifier=Modifier.padding(12.dp))}
    }
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).navigationBarsPadding().padding(bottom=20.dp)){
            Text("播放设置",fontSize=21.sp);Text("倍速",fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
            Row(Modifier.fillMaxWidth()){listOf(.75f,1f,1.25f,1.5f,2f).forEach{rate->TextButton(onClick={player?.setPlaybackSpeed(rate)},modifier=Modifier.weight(1f),contentPadding=PaddingValues(2.dp)){Text("${rate}×",fontSize=12.sp,color=if(speed==rate)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}
            Text("画面中央双击播放 / 暂停；长按临时加速，松手恢复。",fontSize=11.sp,modifier=Modifier.padding(vertical=8.dp))
            TextButton(onClick={settings=false;onDownload()},enabled=info.offlineId.isBlank()&&info.entry?.sourceId?.startsWith("demo-")!=true){Text(if(info.offlineId.isNotBlank())"当前为离线视频"else"下载当前集 / 离线观看")}
            TextButton(onClick={settings=false;onSources()}){Text("查找其他来源 / 换源")}
            Row(verticalAlignment=Alignment.CenterVertically){Text("后台播放",Modifier.weight(1f));Switch(background,onCheckedChange=onBackground)}
            Row(verticalAlignment=Alignment.CenterVertically){Text("离开时自动小窗",Modifier.weight(1f));Switch(autoPip,onCheckedChange=onAutoPip)}
            Row(verticalAlignment=Alignment.CenterVertically){Text("过滤已识别的广告片段",Modifier.weight(1f));Switch(info.filter,onCheckedChange={onFilter()})}
            Text(if(!info.hls)"此格式无法自动识别压入画面的贴片广告"else info.report?.message()?:"播放时检测广告分片",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row{TextButton(onClick=onFilter){Text("广告过滤说明")};Spacer(Modifier.weight(1f));TextButton(onClick={settings=false;onStop()}){Text("停止播放")}}
        }
    }
}
