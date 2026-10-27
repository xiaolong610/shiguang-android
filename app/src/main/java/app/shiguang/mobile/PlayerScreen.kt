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
        if(!fullscreen)Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,tr(R.string.ui_0226))};Text(info.entry?.title?:tr(R.string.ui_0664),fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));IconButton(onClick={settings=true}){Icon(Icons.Outlined.Settings,tr(R.string.ui_0665))}}
        val videoModifier=if(fullscreen)Modifier.fillMaxWidth().weight(1f)else Modifier.fillMaxWidth().aspectRatio(16f/9f)
        Box(videoModifier.background(Color.Black)){
            if(player==null)CircularProgressIndicator(Modifier.align(Alignment.Center))
            else AndroidView(factory={GesturePlayerView(it).apply{this.player=player;setShowSubtitleButton(true);setControllerVisibilityListener(PlayerView.ControllerVisibilityListener{visibility->controls=visibility==View.VISIBLE})}},update={it.player=player;it.useController=!inPip;it.gesturesEnabled=!inPip;it.onBoost=onBoost},onRelease={it.releaseBoost();it.player=null},modifier=Modifier.fillMaxSize())
            if(info.boosting&&!inPip)Text(tr(R.string.ui_0666, info.speed),color=Color.White,fontSize=13.sp,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.55f)).padding(10.dp))
            if(landscape&&!inPip&&controls)IconButton(onClick=onBack,modifier=Modifier.align(Alignment.TopStart)){Icon(Icons.AutoMirrored.Outlined.ArrowBack,tr(R.string.ui_0667),tint=Color.White)}
            if(!inPip&&controls)Row(Modifier.align(Alignment.TopEnd)){
                IconButton(onClick=onPip){Icon(Icons.Outlined.PictureInPictureAlt,tr(R.string.ui_0668),tint=Color.White)}
                IconButton(onClick=onFullscreen){Icon(if(landscape)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,if(landscape)tr(R.string.ui_0669)else tr(R.string.ui_0670),tint=Color.White)}
                if(landscape)IconButton(onClick={settings=true}){Icon(Icons.Outlined.Settings,tr(R.string.ui_0665),tint=Color.White)}
            }
        }
        if(!fullscreen){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(info.entry?.title.orEmpty(),fontSize=19.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(info.title,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)};TextButton(onClick={settings=true}){Text("${speed}×")}}
            if(info.status.isNotBlank())Text(info.status,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
            (connectionError?:info.error)?.let{message->Column(Modifier.padding(horizontal=16.dp)){Text(message,color=MaterialTheme.colorScheme.error,fontSize=12.sp);Row{TextButton(onClick=onRetry){Text(tr(R.string.ui_0672))};TextButton(onClick=onBrowser){Text(tr(R.string.ui_0399))}}}}
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0674, info.total),fontSize=17.sp,modifier=Modifier.weight(1f));TextButton(onClick={reverse=!reverse;prefs.edit().putBoolean("episodeReverse",reverse).apply()},modifier=Modifier.semantics{contentDescription=tr(R.string.ui_0675)}){Text(if(reverse)tr(R.string.ui_0676)else tr(R.string.ui_0677))}}
            LazyVerticalGrid(columns=GridCells.Adaptive(76.dp),modifier=Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(16.dp,4.dp,16.dp,16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                items(episodeOrder(info.pages.size,reverse),key={it}){index->
                    val selected=index==info.chapter
                    Surface(onClick={onPick(index)},shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().heightIn(min=44.dp).semantics{contentDescription=tr(R.string.ui_0678, index+1)}){
                        Box(Modifier.padding(horizontal=6.dp,vertical=8.dp),contentAlignment=Alignment.Center){Text(info.pages[index].title.ifBlank{tr(R.string.ui_0679, index+1)},fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)}
                    }
                }
            }
        }else if(!inPip)(connectionError?:info.error)?.let{Text(it,color=Color.White,fontSize=12.sp,modifier=Modifier.padding(12.dp))}
    }
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).navigationBarsPadding().padding(bottom=20.dp)){
            Text(tr(R.string.ui_0665),fontSize=21.sp);Text(tr(R.string.ui_0681),fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
            Row(Modifier.fillMaxWidth()){listOf(.75f,1f,1.25f,1.5f,2f).forEach{rate->TextButton(onClick={player?.setPlaybackSpeed(rate)},modifier=Modifier.weight(1f),contentPadding=PaddingValues(2.dp)){Text("${rate}×",fontSize=12.sp,color=if(speed==rate)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}
            Text(tr(R.string.ui_0682),fontSize=11.sp,modifier=Modifier.padding(vertical=8.dp))
            TextButton(onClick={settings=false;onDownload()},enabled=info.offlineId.isBlank()&&info.entry?.sourceId?.startsWith("demo-")!=true){Text(if(info.offlineId.isNotBlank())tr(R.string.ui_0683)else tr(R.string.ui_0684))}
            TextButton(onClick={settings=false;onSources()}){Text(tr(R.string.ui_0685))}
            Row(verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0686),Modifier.weight(1f));Switch(background,onCheckedChange=onBackground)}
            Row(verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0687),Modifier.weight(1f));Switch(autoPip,onCheckedChange=onAutoPip)}
            Row(verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0688),Modifier.weight(1f));Switch(info.filter,onCheckedChange={onFilter()})}
            Text(if(!info.hls)tr(R.string.ui_0689)else info.report?.message()?:tr(R.string.ui_0690),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row{TextButton(onClick=onFilter){Text(tr(R.string.ui_0691))};Spacer(Modifier.weight(1f));TextButton(onClick={settings=false;onStop()}){Text(tr(R.string.ui_0692))}}
        }
    }
}
