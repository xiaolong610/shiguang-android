package app.shiguang.mobile

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity:ComponentActivity() {
    override fun onResume(){super.onResume();ResolverWindow.activity=java.lang.ref.WeakReference(this)}
    private var controller by mutableStateOf<MediaController?>(null)
    private var inPip by mutableStateOf(false)
    private var background by mutableStateOf(true)
    private var autoPip by mutableStateOf(true)
    private var connectionError by mutableStateOf<String?>(null)
    private var future:ListenableFuture<MediaController>?=null
    private var requestId=""
    private var requested=false
    private val prefs by lazy{getSharedPreferences("player",0)}
    private fun returnToWindow(){if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else {command(PlaybackSession.BOOST_END);finish();updatePip()}}
    private fun command(action:String,args:Bundle=Bundle.EMPTY){controller?.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestId=savedInstanceState?.getString("requestId") ?: UUID.randomUUID().toString()
        background=prefs.getBoolean("backgroundPlayback",true);autoPip=prefs.getBoolean("autoPip",true)
        setContent {ShiguangTheme {
            val model:AppModel=viewModel()
            val info by PlaybackSession.state.collectAsState()
            val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
            var filterDialog by remember{mutableStateOf(false)}
            var sourceSheet by remember{mutableStateOf(false)}
            var downloadMessage by remember{mutableStateOf("")};var preparingDownload by remember{mutableStateOf(false)};var downloadJob by remember{mutableStateOf<Job?>(null)};val downloadScope=rememberCoroutineScope()
            BackHandler(!inPip){returnToWindow()}
            LaunchedEffect(info.playing,autoPip){updatePip()}
            LaunchedEffect(landscape,inPip){val bars=WindowInsetsControllerCompat(window,window.decorView);bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;if(landscape&&!inPip)bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())}
            CompactPlayerScreen(info,controller,landscape,inPip,background,autoPip,connectionError,
                onBack={returnToWindow()},onPip={command(PlaybackSession.BOOST_END);enterPip()},
                onFullscreen={requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE},
                onPick={index->command(PlaybackSession.GOTO,Bundle().apply{putInt("index",index)})},
                onFilter={filterDialog=true},onRetry={command(PlaybackSession.RETRY)},
                onBrowser={openBrowser(this@PlayerActivity,info.page)},onStop={command(PlaybackSession.STOP);finish()},
                onBackground={background=it;prefs.edit().putBoolean("backgroundPlayback",it).apply()},
                onAutoPip={autoPip=it;prefs.edit().putBoolean("autoPip",it).apply();updatePip()},
                onBoost={command(if(it)PlaybackSession.BOOST_START else PlaybackSession.BOOST_END)},onSources={sourceSheet=true},onDownload={if(!preparingDownload&&info.entry!=null){preparingDownload=true;downloadMessage="正在准备下载…";downloadJob=downloadScope.launch{try{while(!model.libraryReady&&model.libraryError.isBlank())delay(20);downloadMessage=VideoDownloads.add(this@PlayerActivity,model.source(info.entry!!.sourceId),info.entry!!,info.pages[info.chapter],info.chapter,info.line)}catch(e:CancellationException){throw e}catch(e:Exception){downloadMessage=ContentRepository.friendlyError(e)}finally{preparingDownload=false}}}})
            if(downloadMessage.isNotBlank())AlertDialog(onDismissRequest={downloadJob?.cancel();downloadMessage=""},title={Text("视频下载")},text={Text(downloadMessage)},dismissButton={if(preparingDownload)TextButton(onClick={downloadJob?.cancel();downloadMessage=""}){Text("取消准备")}},confirmButton={TextButton(enabled=!preparingDownload,onClick={downloadMessage=""}){Text("知道了")}})
            if(sourceSheet&&info.entry!=null)SourceSwitchSheet(model,info.entry!!,Detail(info.entry!!,listOf(Line("当前线路",info.pages))),
                progressOverride=Progress(info.entry!!,0,info.chapter,info.positionMs,System.currentTimeMillis(),info.title),onDismiss={sourceSheet=false}){result->
                downloadScope.launch{try{model.applySourceSwitch(info.entry!!,result).await();sourceSheet=false
                val source=model.source(result.detail.entry.sourceId)
                val request=PlaybackRequest(UUID.randomUUID().toString(),result.detail.entry,result.detail.lines[result.line].chapters,result.chapter,result.line,result.offset,source,source?.let{model.repo.headers(it)}?:emptyMap(),source?.let{JSONObject(it.rules).optBoolean("knownAds")}?:false)
                command(PlaybackSession.LOAD,Bundle().apply{putString("request",request.json().toString())})
                }catch(e:CancellationException){throw e}catch(e:Exception){connectionError=ContentRepository.friendlyError(e)}}
            }
            if(filterDialog)AlertDialog(onDismissRequest={filterDialog=false},title={Text("广告过滤")},text={Text("只过滤有充分依据的HLS插播片段。切片不等于广告；压进画面的广告无法识别。若误删，可关闭过滤并重载，进度按原时间线保存。\n\n"+(info.report?.message() ?: ""))},confirmButton={TextButton(onClick={command(PlaybackSession.FILTER);filterDialog=false}){Text(if(info.filter)"关闭过滤并重载" else "开启过滤并重载")}},dismissButton={TextButton(onClick={filterDialog=false}){Text("返回播放")}})
        }}
        val f=MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).buildAsync();future=f
        f.addListener({
            try {if(isDestroyed)return@addListener;controller=f.get();controller?.addListener(object:Player.Listener{override fun onIsPlayingChanged(isPlaying:Boolean){updatePip()}});submitIntent()}
            catch(e:Exception){connectionError="无法连接播放服务：${e.cause?.message ?: e.message}"}
        },ContextCompat.getMainExecutor(this))
    }
    private fun submitIntent() {
        if(requested||!intent.hasExtra("entry"))return
        requested=true
        val loadId=requestId;val currentIntent=Intent(intent)
        lifecycleScope.launch{try {
            val request=withContext(Dispatchers.IO){
            val entry=Entry.parse(JSONObject(currentIntent.getStringExtra("entry") ?: ""))
            val list=JSONArray(currentIntent.getStringExtra("playlist") ?: "[]")
            val pages=if(list.length()>0)(0 until list.length()).map{val c=list.getJSONObject(it);Chapter(c.getString("title"),c.getString("url"))}else listOf(Chapter(currentIntent.getStringExtra("title") ?: entry.title,currentIntent.getStringExtra("url") ?: ""))
            val chapter=if(list.length()>0)currentIntent.getIntExtra("chapter",0).coerceIn(pages.indices) else 0
            val storedChapter=currentIntent.getIntExtra("chapter",0);val line=currentIntent.getIntExtra("line",0)
            val offset=LibraryStore(this@PlayerActivity).use{store->store.progressFor(entry.key)?.takeIf{it.line==line&&it.chapter==storedChapter}?.offset} ?: currentIntent.getLongExtra("offset",0)
            val h=JSONObject(currentIntent.getStringExtra("headers") ?: "{}")
            val source=currentIntent.getStringExtra("source")?.let{SourceCodec.parse(it).single()}
            PlaybackRequest(loadId,entry,pages,chapter,line,offset,source,h.keys().asSequence().associateWith{h.getString(it)},currentIntent.getBooleanExtra("knownAdSource",false),currentIntent.getStringExtra("offlineId").orEmpty(),if(currentIntent.hasExtra("offlineId"))storedChapter else -1)
            };if(requestId==loadId)command(PlaybackSession.LOAD,Bundle().apply{putString("request",request.json().toString())})
        }catch(e:CancellationException){throw e}catch(e:Exception){connectionError=ContentRepository.friendlyError(e)}}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.hasExtra("entry")){requestId=UUID.randomUUID().toString();requested=false;submitIntent()}}
    private fun params():PictureInPictureParams {
        val size=controller?.videoSize;val ratio=if(size!=null&&size.width>0&&size.height>0&&size.width.toDouble()/size.height in 0.42..2.38)Rational(size.width,size.height) else Rational(16,9)
        val b=PictureInPictureParams.Builder().setAspectRatio(ratio)
        if(Build.VERSION.SDK_INT>=31)b.setAutoEnterEnabled(!isFinishing&&autoPip&&controller?.isPlaying==true).setSeamlessResizeEnabled(true)
        return b.build()
    }
    private fun updatePip(){if(packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))runCatching{setPictureInPictureParams(params())}}
    private fun enterPip(){if(packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)&&controller?.currentMediaItem!=null)runCatching{enterPictureInPictureMode(params())}}
    override fun onUserLeaveHint(){super.onUserLeaveHint();if(!isFinishing&&Build.VERSION.SDK_INT<31&&autoPip&&controller?.isPlaying==true)enterPip()}
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode:Boolean,newConfig:Configuration){super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig);val was=inPip;inPip=isInPictureInPictureMode;if(was&&!inPip&&!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))controller?.pause()}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("requestId",requestId);super.onSaveInstanceState(outState)}
    override fun onStop(){command(PlaybackSession.BOOST_END);if(!isChangingConfigurations&&!isInPictureInPictureMode&&!background)controller?.pause();super.onStop()}
    override fun onDestroy(){future?.let{MediaController.releaseFuture(it)};controller=null;super.onDestroy()}
}
