package app.shiguang.mobile

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OfflineVideo(val id:String,val entry:Entry,val chapter:Chapter,val state:Int,val bytes:Long,val percent:Float,val stopped:Boolean=false,val chapterIndex:Int=0,val line:Int=0)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoDownloads {
    private var cache:SimpleCache?=null
    private var manager:DownloadManager?=null
    private val executor=Executors.newFixedThreadPool(2)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var refreshJob:Job?=null
    private var progressJob:Job?=null
    val items=MutableStateFlow(emptyList<OfflineVideo>())
    @Synchronized fun cache(context:Context)=cache?:SimpleCache(File(context.applicationContext.filesDir,"video-downloads"),NoOpCacheEvictor(),StandaloneDatabaseProvider(context.applicationContext)).also{cache=it}
    fun wifiOnly(context:Context)=context.getSharedPreferences("downloads",0).getBoolean("wifi",true)
    fun requirements(context:Context)=Requirements(Requirements.NETWORK or if(wifiOnly(context))Requirements.NETWORK_UNMETERED else 0)
    fun manager(context:Context):DownloadManager {
        manager?.let{return it}
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        val app=context.applicationContext
        val index=DefaultDownloadIndex(StandaloneDatabaseProvider(app))
        val factory=DownloaderFactory{request->
            val metadata=JSONObject(String(request.data,Charsets.UTF_8))
            DefaultDownloaderFactory(CacheDataSource.Factory().setCache(cache(app)).setUpstreamDataSourceFactory(network(app,metadata)),executor).createDownloader(request)
        }
        return DownloadManager(app,index,factory).also{m->
            manager=m;m.maxParallelDownloads=1;m.minRetryCount=2;m.requirements=requirements(app)
            m.addListener(object:DownloadManager.Listener {
                override fun onInitialized(downloadManager:DownloadManager){refresh(app)}
                override fun onDownloadChanged(downloadManager:DownloadManager,download:Download,finalException:Exception?){refresh(app);progress(app)}
                override fun onDownloadRemoved(downloadManager:DownloadManager,download:Download){refresh(app)}
                override fun onIdle(downloadManager:DownloadManager){refresh(app)}
            })
            refresh(app)
        }
    }
    private fun network(context:Context,metadata:JSONObject):DataSource.Factory {
        val headers=metadata.optJSONObject("headers")?:JSONObject();val origin=Uri.parse(metadata.getString("url")).host
        val http=DefaultHttpDataSource.Factory().setUserAgent("Shiguang/0.10 Android").setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
        val resolving=ResolvingDataSource.Factory(http){spec->
            val h=headers.keys().asSequence().filter{!(it.equals("Authorization",true)||it.equals("Cookie",true))||spec.uri.host==origin}.associateWith{headers.getString(it)}.toMutableMap()
            CookieManager.getInstance().getCookie(spec.uri.toString())?.takeIf{it.isNotBlank()}?.let{h["Cookie"]=it}
            spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders+h).build()
        }
        return DataSource.Factory{object:DataSource {
            private val upstream=resolving.createDataSource();private var checked=0L
            private fun space(){if(context.filesDir.usableSpace<128L*1024*1024)throw IOException(tr(R.string.ui_0812))}
            override fun addTransferListener(listener:TransferListener)=upstream.addTransferListener(listener)
            override fun open(dataSpec:DataSpec):Long{space();return upstream.open(dataSpec)}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int{if(checked>=1024*1024){space();checked=0};return upstream.read(buffer,offset,length).also{if(it>0)checked+=it}}
            override fun getUri()=upstream.uri
            override fun getResponseHeaders()=upstream.responseHeaders
            override fun close()=upstream.close()
        }}
    }
    private fun progress(context:Context){if(progressJob?.isActive==true)return;progressJob=scope.launch{while(manager?.currentDownloads?.any{it.state==Download.STATE_DOWNLOADING}==true){refresh(context);delay(1000)}}}
    private fun refresh(context:Context){
        refreshJob?.cancel();refreshJob=scope.launch{
            val live=manager?.currentDownloads?.associateBy{it.request.id}.orEmpty()
            val values=withContext(Dispatchers.IO){val m=manager?:return@withContext emptyList();m.downloadIndex.getDownloads().use{c->buildList{while(c.moveToNext()){
                val d=live[c.download.request.id]?:c.download;runCatching{
                val progressive=androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(d.request.uri,d.request.mimeType)==androidx.media3.common.C.CONTENT_TYPE_OTHER
                val durable=if(progressive)cache(context).getCachedBytes(d.request.customCacheKey?:d.request.uri.toString(),0,androidx.media3.common.C.LENGTH_UNSET.toLong())else d.bytesDownloaded
                val bytes=if(d.state==Download.STATE_DOWNLOADING)maxOf(durable,d.bytesDownloaded)else durable
                val percent=if(d.contentLength>0)(bytes*100f/d.contentLength).coerceIn(0f,100f)else d.percentDownloaded
                val meta=JSONObject(String(d.request.data,Charsets.UTF_8));OfflineVideo(d.request.id,Entry.parse(meta.getJSONObject("entry")),Chapter(meta.getString("title"),meta.getString("page")),d.state,bytes,percent,d.stopReason!=0,meta.optInt("chapterIndex"),meta.optInt("line"))}.getOrNull()?.let{add(it)}
            }}}}
            items.value=values
        }
    }
    suspend fun add(context:Context,source:Source?,entry:Entry,chapter:Chapter,chapterIndex:Int=0,line:Int=0):String {
        require(entry.kind==Kind.VIDEO){tr(R.string.ui_0813)}
        val app=context.applicationContext
        val id=SourceCodec.stableId(entry.key+"|"+chapter.url)
        val existingManager=withContext(Dispatchers.Main.immediate){manager(app)}
        if(withContext(Dispatchers.IO){existingManager.downloadIndex.getDownload(id)?.state}==Download.STATE_COMPLETED)return tr(R.string.ui_0814)
        if(wifiOnly(app)){val connectivity=app.getSystemService(android.net.ConnectivityManager::class.java);require(connectivity.activeNetwork!=null&&!connectivity.isActiveNetworkMetered){tr(R.string.ui_0815)}}
        require(withContext(Dispatchers.IO){app.filesDir.usableSpace}>=256L*1024*1024){tr(R.string.ui_0816)}
        val resolved=WebVideoResolver(context).resolve(source,chapter.url){}
        val headers=source?.let{ContentRepository(app).headers(it)}.orEmpty()+resolved.headers
        val metadata=JSONObject().put("entry",entry.json()).put("title",chapter.title).put("page",chapter.url).put("url",resolved.url).put("headers",JSONObject(headers)).put("chapterIndex",chapterIndex).put("line",line)
        return withContext(Dispatchers.Main.immediate){
            val m=manager(app)
            val old=withContext(Dispatchers.IO){m.downloadIndex.getDownload(id)}
            if(old?.state==Download.STATE_COMPLETED)return@withContext tr(R.string.ui_0814)
            val item=MediaItem.Builder().setUri(resolved.url).apply{if(resolved.mime.isNotBlank())setMimeType(resolved.mime)}.build()
            val request=if(androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(Uri.parse(resolved.url),resolved.mime.takeIf{it.isNotBlank()})==androidx.media3.common.C.CONTENT_TYPE_OTHER){
                DownloadRequest.Builder(id,Uri.parse(resolved.url)).setMimeType(resolved.mime.takeIf{it.isNotBlank()}).setData(metadata.toString().toByteArray()).build()
            }else{
            val parameters=DownloadHelper.getDefaultTrackSelectorParameters(app).buildUpon().setForceHighestSupportedBitrate(true).setMaxVideoSize(1920,1080).build()
            val helper=DownloadHelper.forMediaItem(item,parameters,DefaultRenderersFactory(app),network(app,metadata))
            try{withTimeout(30_000){suspendCancellableCoroutine<DownloadRequest>{continuation->
                continuation.invokeOnCancellation{android.os.Handler(android.os.Looper.getMainLooper()).post{helper.release()}}
                helper.prepare(object:DownloadHelper.Callback {
                    override fun onPrepared(helper:DownloadHelper,tracksInfoAvailable:Boolean){if(continuation.isActive)try{
                        val selected=helper.getDownloadRequest(id,metadata.toString().toByteArray())
                        val manifest=helper.manifest
                        if(manifest is androidx.media3.exoplayer.hls.HlsManifest&&manifest.multivariantPlaylist.variants.size>1)require(selected.streamKeys.any{it.groupIndex==0}){tr(R.string.ui_0818)}
                        continuation.resume(selected)
                    }catch(e:Exception){continuation.resumeWithException(e)}}
                    override fun onPrepareError(helper:DownloadHelper,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                })
            }}}finally{helper.release()}
            }
            DownloadService.sendAddDownload(app,VideoDownloadService::class.java,request,true)
            DownloadService.sendResumeDownloads(app,VideoDownloadService::class.java,true)
            tr(R.string.ui_0819, if(wifiOnly(app))tr(R.string.ui_0820)else tr(R.string.ui_0821))
        }
    }
    fun pause(context:Context,id:String)=DownloadService.sendSetStopReason(context,VideoDownloadService::class.java,id,1,false)
    fun resume(context:Context,id:String){val m=manager(context);scope.launch{val old=withContext(Dispatchers.IO){m.downloadIndex.getDownload(id)};if(old?.state==Download.STATE_FAILED)DownloadService.sendAddDownload(context,VideoDownloadService::class.java,old.request,true);DownloadService.sendSetStopReason(context,VideoDownloadService::class.java,id,0,true);DownloadService.sendResumeDownloads(context,VideoDownloadService::class.java,true)}}
    fun remove(context:Context,id:String)=DownloadService.sendRemoveDownload(context,VideoDownloadService::class.java,id,false)
    fun wifi(context:Context,value:Boolean){context.getSharedPreferences("downloads",0).edit().putBoolean("wifi",value).apply();manager(context).requirements=requirements(context)}
    suspend fun completed(context:Context,id:String):DownloadRequest = withContext(Dispatchers.IO){
        val m=withContext(Dispatchers.Main.immediate){manager(context)}
        val d=m.downloadIndex.getDownload(id)?:error(tr(R.string.ui_0822))
        require(d.state==Download.STATE_COMPLETED){tr(R.string.ui_0823)};d.request
    }
    fun play(context:Context,item:OfflineVideo){
        require(item.state==Download.STATE_COMPLETED)
        context.startActivity(Intent(context,PlayerActivity::class.java).putExtra("entry",item.entry.json().toString()).putExtra("url",item.chapter.url).putExtra("title",item.chapter.title).putExtra("offlineId",item.id).putExtra("chapter",item.chapterIndex).putExtra("line",item.line))
    }
    fun bytes():Long=cache?.cacheSpace?:0L
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDownloadService:DownloadService(33,1000,"shiguang-downloads",R.string.download_channel,0) {
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(AppLanguage.wrap(base))}
    override fun onTimeout(startId:Int,fgsType:Int){VideoDownloads.manager(this).pauseDownloads();stopSelf()}
    override fun getDownloadManager()=VideoDownloads.manager(this)
    override fun getScheduler():Scheduler?=null
    override fun getForegroundNotification(downloads:MutableList<Download>,notMetRequirements:Int):Notification {
        val intent=PendingIntent.getActivity(this,32,Intent(this,MainActivity::class.java).putExtra("openDownloads",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return DownloadNotificationHelper(this,"shiguang-downloads").buildProgressNotification(this,android.R.drawable.stat_sys_download,intent,tr(R.string.ui_0824),downloads,notMetRequirements)
    }
}
