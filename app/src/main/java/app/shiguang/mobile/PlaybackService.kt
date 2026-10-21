package app.shiguang.mobile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.media3.common.*
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class PlaybackInfo(val entry:Entry?=null,val title:String="",val error:String?=null,val status:String="",val filter:Boolean=true,val report:AdFilterResult?=null,val hls:Boolean=false,val chapter:Int=0,val total:Int=1,val playing:Boolean=false,val requestId:String="",val rendered:Boolean=false,val page:String="",val pages:List<Chapter> = emptyList())
object PlaybackSession {
    val state=MutableStateFlow(PlaybackInfo())
    const val LOAD="app.shiguang.LOAD"
    const val FILTER="app.shiguang.FILTER"
    const val NEXT="app.shiguang.NEXT"
    const val PREVIOUS="app.shiguang.PREVIOUS"
    const val RETRY="app.shiguang.RETRY"
    const val STOP="app.shiguang.STOP"
    const val GOTO="app.shiguang.GOTO"
}
data class PlaybackRequest(val id:String,val entry:Entry,val pages:List<Chapter>,val chapter:Int,val line:Int,val offset:Long,val source:Source?,val headers:Map<String,String>,val knownAds:Boolean) {
    fun json()=JSONObject().put("id",id).put("entry",entry.json()).put("pages",JSONArray().apply{pages.forEach{put(JSONObject().put("title",it.title).put("url",it.url))}}).put("chapter",chapter).put("line",line).put("offset",offset).put("source",source?.json()).put("headers",JSONObject(headers)).put("knownAds",knownAds)
    companion object {
        fun parse(o:JSONObject):PlaybackRequest {
            val a=o.getJSONArray("pages");require(a.length() in 1..3000)
            val pages=(0 until a.length()).map{val ch=a.getJSONObject(it);Chapter(ch.getString("title"),ch.getString("url"))}
            val h=o.optJSONObject("headers") ?: JSONObject()
            return PlaybackRequest(o.getString("id"),Entry.parse(o.getJSONObject("entry")),pages,o.getInt("chapter").coerceIn(pages.indices),o.optInt("line"),o.optLong("offset"),o.optJSONObject("source")?.let{SourceCodec.parse(it.toString()).single()},h.keys().asSequence().associateWith{h.getString(it)},o.optBoolean("knownAds"))
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoCache {
    private var cache:SimpleCache?=null
    @Synchronized fun get(context:android.content.Context):SimpleCache=cache ?: SimpleCache(File(context.applicationContext.cacheDir,"media"),LeastRecentlyUsedCacheEvictor(256L*1024*1024),StandaloneDatabaseProvider(context.applicationContext)).also{cache=it}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService() {
    private lateinit var exo:ExoPlayer
    private lateinit var session:MediaSession
    private lateinit var store:LibraryStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var resolver:Job?=null
    private var request:PlaybackRequest?=null
    private val reports=mutableMapOf<String,AdFilterResult>()
    private val filter=AtomicBoolean(true)
    private var pendingSeek:Pair<String,Long>?=null
    private var directList=false
    private var trackedChapter=0
    private var lastPosition=0L
    private var lastDuration=0L
    private val prefs by lazy{getSharedPreferences("player",0)}
    private fun update(change:(PlaybackInfo)->PlaybackInfo){PlaybackSession.state.value=change(PlaybackSession.state.value)}
    private fun hls(item:MediaItem?)=item?.localConfiguration?.let{it.mimeType==MimeTypes.APPLICATION_M3U8||it.uri.path?.endsWith(".m3u8",true)==true} ?: false
    private fun key()=exo.currentMediaItem?.mediaId.orEmpty()
    private fun originalPosition():Long=reports[key()]?.filteredToOriginalMs(exo.currentPosition) ?: exo.currentPosition
    override fun onCreate() {
        super.onCreate();store=LibraryStore(this);filter.set(prefs.getBoolean("filterAds",true))
        exo=ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            setHandleAudioBecomingNoisy(true);setWakeMode(C.WAKE_MODE_NETWORK);setPlaybackSpeed(prefs.getFloat("speed",1f))
        }
        val notificationIntent=PendingIntent.getActivity(this,1,Intent(this,PlayerActivity::class.java).putExtra("resume",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session=MediaSession.Builder(this,exo).setSessionActivity(notificationIntent).setCallback(object:MediaSession.Callback {
            override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                val commands=MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if(controller.packageName==packageName)listOf(PlaybackSession.LOAD,PlaybackSession.FILTER,PlaybackSession.NEXT,PlaybackSession.PREVIOUS,PlaybackSession.RETRY,PlaybackSession.STOP,PlaybackSession.GOTO).forEach{commands.add(SessionCommand(it,Bundle.EMPTY))}
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
            }
            override fun onCustomCommand(session:MediaSession,controller:MediaSession.ControllerInfo,command:SessionCommand,args:Bundle):ListenableFuture<SessionResult> {
                if(controller.packageName!=packageName)return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                return try {
                    when(command.customAction) {
                        PlaybackSession.LOAD->{val next=PlaybackRequest.parse(JSONObject(args.getString("request") ?: error("请求为空")));if(next.id!=request?.id)load(next)}
                        PlaybackSession.FILTER->{filter.set(!filter.get());prefs.edit().putBoolean("filterAds",filter.get()).apply();request?.let{load(it.copy(id=UUID.randomUUID().toString(),chapter=trackedChapter,offset=originalPosition()))}}
                        PlaybackSession.NEXT->next(1)
                        PlaybackSession.PREVIOUS->next(-1)
                        PlaybackSession.GOTO->{val index=args.getInt("index",-1);require(index in (request?.pages?.indices?:IntRange.EMPTY)){"集数已变更"};next(index-trackedChapter)}
                        PlaybackSession.RETRY->request?.let{load(it.copy(id=UUID.randomUUID().toString(),chapter=trackedChapter,offset=if(pendingSeek!=null)it.offset else originalPosition()))}
                        PlaybackSession.STOP->{save();resolver?.cancel();request=null;exo.stop();exo.clearMediaItems();PlaybackSession.state.value=PlaybackInfo();Handler(Looper.getMainLooper()).postDelayed({stopSelf()},100)}
                        else->return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }catch(e:Exception){update{it.copy(error=ContentRepository.friendlyError(e))};Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))}
            }
        }).build()
        exo.addListener(object:Player.Listener {
            override fun onRenderedFirstFrame(){update{it.copy(rendered=true)}}
            override fun onPlayerError(error:PlaybackException){update{it.copy(error="播放失败：${error.errorCodeName}。可重新解析或换线路。",status="",playing=false)}}
            override fun onIsPlayingChanged(isPlaying:Boolean){update{it.copy(playing=isPlaying)}}
            override fun onPlaybackParametersChanged(playbackParameters:PlaybackParameters){prefs.edit().putFloat("speed",playbackParameters.speed).apply()}
            override fun onMediaItemTransition(item:MediaItem?,reason:Int) {
                val r=request ?: return
                if(directList){
                    if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)store.save(r.entry,r.line,trackedChapter,lastDuration.coerceAtLeast(lastPosition))
                    trackedChapter=exo.currentMediaItemIndex.coerceIn(r.pages.indices)
                }
                update{it.copy(title=item?.mediaMetadata?.title?.toString().orEmpty(),chapter=trackedChapter,hls=hls(item),report=reports[key()],page=r.pages[trackedChapter].url)}
            }
            override fun onPlaybackStateChanged(state:Int) {
                val seek=pendingSeek
                if(state==Player.STATE_READY&&seek!=null&&seek.first==key()) {
                    pendingSeek=null;val target=reports[key()]?.originalToFilteredMs(seek.second) ?: seek.second
                    if(target>0)exo.seekTo(target)
                }
                if(state==Player.STATE_ENDED){save();if(!directList&&trackedChapter<(request?.pages?.lastIndex ?: 0))next(1)}
            }
        })
        scope.launch{while(isActive){delay(2000);save();if(exo.duration>0)lastDuration=reports[key()]?.filteredToOriginalMs(exo.duration) ?: exo.duration}}
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession?=if(controllerInfo.packageName==packageName||controllerInfo.isTrusted)session else null
    private fun dataFactory(headers:Map<String,String>,origin:String):DataSource.Factory {
        val http=DefaultHttpDataSource.Factory().setUserAgent("Shiguang/0.3 Android").setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val scoped=ResolvingDataSource.Factory(DefaultDataSource.Factory(this,http)){spec->
            val map=headers.filterKeys{!it.equals("Cookie",true)&&!it.equals("Authorization",true)}.toMutableMap()
            val sameHost=spec.uri.host==Uri.parse(origin).host
            if(sameHost)headers.filterKeys{it.equals("Authorization",true)||it.equals("Cookie",true)}.forEach{(k,v)->map[k]=v}
            CookieManager.getInstance().getCookie(spec.uri.toString())?.takeIf{it.isNotBlank()}?.let{map["Cookie"]=it}
            spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders+map).build()
        }
        return CacheDataSource.Factory().setCache(VideoCache.get(this)).setUpstreamDataSourceFactory(scoped).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    private fun mediaSource(r:PlaybackRequest,ch:Chapter,video:ResolvedVideo):MediaSource {
        val item=MediaItem.Builder().setMediaId(video.url).setUri(video.url).apply{if(video.mime.isNotBlank())setMimeType(video.mime)}.setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title).setArtist(r.entry.title).build()).build()
        val data=dataFactory(video.headers,video.url)
        if(!hls(item))return DefaultMediaSourceFactory(data).createMediaSource(item)
        val factory=AdFilteringPlaylistParserFactory({filter.get()},r.knownAds){_,report->Handler(Looper.getMainLooper()).post{
            if(request?.id==r.id){reports[video.url]=report;if(key()==video.url)update{it.copy(report=report)}}
        }}
        return HlsMediaSource.Factory(data).setPlaylistParserFactory(factory).createMediaSource(item)
    }
    private fun load(r:PlaybackRequest) {
        save();resolver?.cancel();exo.pause();exo.stop();exo.clearMediaItems();pendingSeek=null
        request=r;trackedChapter=r.chapter;lastPosition=r.offset;lastDuration=0;reports.clear()
        PlaybackSession.state.value=PlaybackInfo(r.entry,r.pages[r.chapter].title,status="正在准备播放…",filter=filter.get(),chapter=r.chapter,total=r.pages.size,requestId=r.id,page=r.pages[r.chapter].url,pages=r.pages)
        resolver=scope.launch {
            try {
                directList=r.pages.all{WebVideoResolver.direct(it.url)||it.url.startsWith("asset:///")}
                val sources=if(directList)r.pages.map {ch->mediaSource(r,ch,ResolvedVideo(ch.url,r.headers))} else {
                    val page=r.pages[r.chapter]
                    val video=WebVideoResolver(this@PlaybackService).resolve(r.source,page.url){status->update{it.copy(status=status)}}
                    listOf(mediaSource(r,page,video.copy(headers=r.headers+video.headers)))
                }
                ensureActive();if(request?.id!=r.id)return@launch
                val index=if(directList)r.chapter else 0
                // Set the seek key before prepare, but after building the new playlist.
                exo.setMediaSources(sources,index,0L)
                pendingSeek=exo.currentMediaItem?.mediaId.orEmpty() to r.offset
                update{it.copy(status="",hls=hls(exo.currentMediaItem),filter=filter.get())}
                exo.prepare();exo.play()
            }catch(e:CancellationException){throw e}catch(e:Exception){if(request?.id==r.id)update{it.copy(error=ContentRepository.friendlyError(e),status="",playing=false)}}
        }
    }
    private fun next(delta:Int) {
        val r=request ?: return
        val index=(trackedChapter+delta).coerceIn(r.pages.indices)
        if(index==trackedChapter)return
        save()
        if(directList){pendingSeek=null;exo.seekToDefaultPosition(index);exo.play()}else load(r.copy(id=UUID.randomUUID().toString(),chapter=index,offset=0))
    }
    private fun save() {
        val r=request ?: return
        if(pendingSeek!=null||exo.currentMediaItem==null)return
        lastPosition=originalPosition();store.save(r.entry,r.line,trackedChapter,lastPosition)
    }
    override fun onTaskRemoved(rootIntent:Intent?) {if(!exo.playWhenReady||exo.mediaItemCount==0)stopSelf()}
    override fun onDestroy(){save();request=null;scope.cancel();session.release();exo.release();store.close();PlaybackSession.state.value=PlaybackInfo();super.onDestroy()}
}
