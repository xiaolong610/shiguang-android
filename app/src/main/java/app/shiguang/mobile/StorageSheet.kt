package app.shiguang.mobile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.*
import java.io.File

data class StorageSizes(val images:Long=0,val streaming:Long=0,val memory:Long=0,val downloads:Long=0)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AppStorage {
    fun format(bytes:Long):String=if(bytes<1024*1024)"%.1f KiB".format(bytes/1024.0)else if(bytes<1024L*1024*1024)"%.1f MiB".format(bytes/(1024.0*1024))else "%.2f GiB".format(bytes/(1024.0*1024*1024))
    private fun disk(file:File)=if(file.exists())file.walkTopDown().filter{it.isFile}.sumOf{it.length()}else 0L
    suspend fun sizes(context:android.content.Context)=withContext(Dispatchers.IO){StorageSizes(AppImages.get(context).diskCache?.size?:0,disk(File(context.cacheDir,"media")),ReadingPageCache.shared.sizeBytes()+DetailMemoryCache.shared.sizeBytes(),disk(File(context.filesDir,"video-downloads")))}
    suspend fun clear(context:android.content.Context,kind:String)=withContext(Dispatchers.IO){when(kind){
        "images"->{AppImages.get(context).memoryCache?.clear();AppImages.get(context).diskCache?.clear()}
        "streaming"->{require(PlaybackSession.state.value.entry==null){"请先停止播放器，再清理播放缓存"};val cache=VideoCache.get(context);cache.keys.toList().forEach{cache.removeResource(it)}}
        "memory"->{ReadingPageCache.shared.clear();DetailMemoryCache.shared.clear()}
    }}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StorageSheet(onDismiss:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var tab by remember{mutableIntStateOf(0)};var sizes by remember{mutableStateOf(StorageSizes())}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var deleting by remember{mutableStateOf<OfflineVideo?>(null)}
    val playback by PlaybackSession.state.collectAsState();val downloads by VideoDownloads.items.collectAsState();var wifi by remember{mutableStateOf(VideoDownloads.wifiOnly(context))}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(Unit){sizes=AppStorage.sizes(context);withContext(Dispatchers.Main.immediate){VideoDownloads.manager(context)}}
    fun clear(kind:String){busy=true;scope.launch{try{AppStorage.clear(context,kind);sizes=AppStorage.sizes(context);message="已清理，本地书籍和视频下载已保留"}catch(e:Exception){message=ContentRepository.friendlyError(e)}finally{busy=false}}}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).navigationBarsPadding().padding(horizontal=20.dp)){
            Text("下载与缓存",fontSize=21.sp)
            TabRow(tab){Tab(tab==0,{tab=0},text={Text("视频下载")});Tab(tab==1,{tab=1},text={Text("缓存管理")})}
            if(message.isNotBlank())Text(message,fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
            if(tab==0){
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("仅Wi-Fi下载",Modifier.weight(1f));Switch(wifi,{wifi=it;VideoDownloads.wifi(context,it)})}
                Text("在播放器设置中下载当前集。下载完成后可断网播放。",fontSize=11.sp)
                if(Build.VERSION.SDK_INT>=33)TextButton(onClick={permission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("开启下载通知",fontSize=12.sp)}
                if(downloads.isEmpty())Text("还没有下载任务",Modifier.padding(vertical=32.dp))
                LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){
                    items(downloads,key={it.id}){item->
                        Column(Modifier.fillMaxWidth().padding(vertical=12.dp)){
                            Text(item.entry.title,fontSize=15.sp);Text(item.chapter.title,fontSize=12.sp)
                            val status=when(item.state){Download.STATE_COMPLETED->"已完成";Download.STATE_FAILED->"下载失败，可继续重试";Download.STATE_DOWNLOADING->"下载中";Download.STATE_REMOVING->"正在删除";else->if(item.stopped)"已暂停"else if(wifi)"排队或等待Wi-Fi"else"排队中"}
                            Text("$status · ${AppStorage.format(item.bytes)}"+(if(item.percent>=0)" · ${item.percent.toInt()}%"else""),fontSize=11.sp)
                            if(item.state==Download.STATE_DOWNLOADING)LinearProgressIndicator(progress={if(item.percent>=0)item.percent/100f else 0f},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                            Row{
                                if(item.state==Download.STATE_COMPLETED)TextButton(onClick={VideoDownloads.play(context,item)}){Text("离线播放")}
                                else if(item.state!=Download.STATE_REMOVING){if(item.stopped||item.state==Download.STATE_FAILED)TextButton(onClick={VideoDownloads.resume(context,item.id)}){Text("继续")}else TextButton(onClick={VideoDownloads.pause(context,item.id)}){Text("暂停")}}
                                Spacer(Modifier.weight(1f));TextButton(onClick={deleting=item},enabled=item.state!=Download.STATE_REMOVING){Text("删除")}
                            };HorizontalDivider()
                        }
                    }
                }
            }else{
                Text("清理临时缓存会保留收藏、进度、本地小说、字体和已下载视频。",fontSize=12.sp,modifier=Modifier.padding(vertical=14.dp))
                listOf(Triple("images","封面与漫画图片",sizes.images),Triple("streaming","在线播放临时缓存",sizes.streaming),Triple("memory","正文与目录内存缓存",sizes.memory)).forEach{(kind,title,bytes)->Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontSize=14.sp);Text(AppStorage.format(bytes),fontSize=11.sp)};TextButton(enabled=!busy&&(kind!="streaming"||playback.entry==null),onClick={clear(kind)}){Text("清理")}}}
                Text("已下载视频：${AppStorage.format(sizes.downloads)}，请在“视频下载”中逐项管理。",fontSize=12.sp,modifier=Modifier.padding(vertical=16.dp))
                Text("图片磁盘缓存上限192MiB；在线播放临时缓存上限256MiB。",fontSize=11.sp)
                TextButton(onClick={scope.launch{sizes=AppStorage.sizes(context)}},enabled=!busy){Text("刷新占用")}
                Spacer(Modifier.height(30.dp))
            }
        }
    }
    deleting?.let{item->AlertDialog(onDismissRequest={deleting=null},title={Text("删除此视频下载？")},text={Text("${item.entry.title} · ${item.chapter.title}\n收藏和观看进度会保留。")},confirmButton={TextButton(onClick={VideoDownloads.remove(context,item.id);deleting=null}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}
