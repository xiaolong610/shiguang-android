package app.shiguang.mobile

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.speech.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class NarrationState(
    val entry:Entry?=null,val chapter:Int=0,val offset:Int=0,val playing:Boolean=false,val loading:Boolean=false,
    val message:String="",val voices:List<String> = emptyList(),val voice:String="",val rate:Float=1f,val sleepUntil:Long=0,
    val mode:String="system",val style:String="natural",val active:Boolean=false,
    val chapterTitle:String="",val chapterCount:Int=0,val characters:Int=0,
)
object Narration {
    private val mutable=MutableStateFlow(NarrationState())
    val state=mutable.asStateFlow()
    internal fun update(value:NarrationState){mutable.value=value}
    fun command(context:Context,action:String,value:String=""){context.startService(Intent(context,NarrationService::class.java).setAction(action).putExtra("value",value))}
    suspend fun changeMode(context:Context,mode:String){
        require(mode in setOf("system","ai"));val before=state.value;require(before.entry!=null){"请先选择一本小说"}
        val raw=withContext(Dispatchers.IO){JSONObject(File(context.filesDir,"narration-request.json").readText())}
        val d=Detail.parse(raw.getJSONObject("detail"));require(d.entry.key==before.entry.key){"听书内容已变更"}
        val s=raw.getJSONObject("source");val source=when(s.optString("engine")){"demo"->Demo.sources.single{it.id==s.getString("id")};"local"->LocalNovels.source;else->SourceCodec.parse(s.toString()).single()}
        val line=raw.getInt("line");val page=ContentRepository(context).page(source,d.entry,d.lines[line].chapters[before.chapter])
        require(state.value.entry?.key==before.entry.key&&state.value.chapter==before.chapter){"章节已变更，请重试"}
        start(context,source,d,line,before.chapter,page.text,state.value.offset.coerceIn(0,page.text.length),mode)
    }
    fun start(context:Context,source:Source,detail:Detail,line:Int,chapter:Int,text:String,offset:Int,mode:String="system"){
        require(mode in setOf("system","ai"))
        val request=JSONObject().put("source",source.json()).put("detail",detail.json()).put("line",line).put("chapter",chapter).put("text",text).put("offset",offset).put("mode",mode)
        val tmp=File(context.filesDir,"narration-request.tmp");tmp.writeText(request.toString());check(tmp.renameTo(File(context.filesDir,"narration-request.json"))){"无法保存听书请求"}
        context.startForegroundService(Intent(context,NarrationService::class.java).setAction("start"))
    }
}

class NarrationService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private var tts:TextToSpeech?=null
    private var ready=false
    private var initializing=false
    private var destroyed=false
    private var detail:Detail?=null
    private var source:Source?=null
    private var line=0
    private var chapter=0
    private var chapterReady=false
    private var text=""
    private var pieces=emptyList<SpeechPiece>()
    private var cursor=0
    private var charOffset=0
    private var generation=0
    private var load:Job?=null
    private var timer:Job?=null
    private var wants=false
    private var isForeground=false
    private var mode="system"
    private var aiConfig:AiVoiceConfig?=null
    private var aiQueue:AiNarrationQueue?=null
    private lateinit var session:MediaSession
    private val audio by lazy{getSystemService(AudioManager::class.java)}
    private val prefs by lazy{getSharedPreferences("narration",0)}
    private var rate=1f
    private var selectedVoice=""
    private var lastNotification=""
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus by lazy{AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener{if(it<0)pause("其他音频正在播放，听书已暂停")}.build()}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){pause("耳机已断开")}}
    private val lock by lazy{getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Shiguang:Narration")}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate();rate=prefs.getFloat("rate",1f).coerceIn(.5f,2f);selectedVoice=prefs.getString("voice","").orEmpty()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("narration","小说听书",NotificationManager.IMPORTANCE_LOW))
        session=MediaSession(this,"拾光听书").apply{
            setCallback(object:MediaSession.Callback(){override fun onPlay(){resume()};override fun onPause(){pause()};override fun onStop(){stopSelf()};override fun onSkipToNext(){changeChapter(chapter+1)};override fun onSkipToPrevious(){changeChapter(chapter-1)}});isActive=true
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),RECEIVER_NOT_EXPORTED)else registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    private fun initializeSystemVoice(){
        if(initializing||ready)return
        initializing=true;tts?.shutdown()
        tts=TextToSpeech(this){status->handler.post{
            initializing=false;if(destroyed||mode!="system")return@post
            if(status!=TextToSpeech.SUCCESS){fail("手机语音引擎不可用，请在系统设置中安装文字转语音引擎");return@post}
            val voices=offlineVoices()
            if(voices.isEmpty()){fail("未找到已安装的离线中文语音，请到系统文字转语音设置下载中文语音包");return@post}
            ready=true;tts?.setAudioAttributes(attributes)
            if(voices.none{it.name==selectedVoice})selectedVoice=voices.first().name
            tts?.voice=voices.first{it.name==selectedVoice};tts?.setSpeechRate(rate)
            publish();if(wants)resume()
        }}
        tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String?){handler.post{if(valid(id))publish(playing=true,message="离线中文朗读")}}
            override fun onDone(id:String?){handler.post{if(valid(id)&&wants){charOffset=pieces[cursor].end;cursor++;save();speakNext()}}}
            @Deprecated("Platform compatibility") override fun onError(id:String?){handler.post{if(valid(id))fail("语音合成失败，请更换系统语音或重试")}}
            override fun onRangeStart(id:String?,start:Int,end:Int,frame:Int){handler.post{if(valid(id)){charOffset=(pieces[cursor].start+start).coerceIn(0,text.length);publish(playing=true)}}}
        })
    }
    private fun offlineVoices()=tts?.voices.orEmpty().filter{it.locale.language==Locale.CHINESE.language&&!it.isNetworkConnectionRequired&&!it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}.sortedWith(compareByDescending<Voice>{it.quality}.thenBy{it.name})
    private fun valid(id:String?)=!destroyed&&mode=="system"&&id=="$generation:$cursor"&&cursor in pieces.indices
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            "start"->{foreground();try{startRequest()}catch(e:Exception){fail("无法开始听书：${ContentRepository.friendlyError(e)}")}}
            "play"->resume()
            "pause"->pause()
            "toggle"->if(wants)pause()else resume()
            "stop"->stopSelf()
            "next"->changeChapter(chapter+1)
            "previous"->changeChapter(chapter-1)
            "seek"->seek(intent.getStringExtra("value")?.toIntOrNull()?:0)
            "rate"->{rate=intent.getStringExtra("value")?.toFloatOrNull()?.coerceIn(.5f,2f)?:1f;prefs.edit().putFloat("rate",rate).apply();if(mode=="ai"){aiQueue?.rate(rate);publish()}else{tts?.setSpeechRate(rate);restartSystemVoice()}}
            "voice"->if(mode=="system"){val name=intent.getStringExtra("value");offlineVoices().find{it.name==name}?.let{tts?.voice=it;selectedVoice=it.name;prefs.edit().putString("voice",it.name).apply();restartSystemVoice()}}
            "sleep"->sleep(intent.getStringExtra("value")?.toIntOrNull()?.coerceIn(0,120)?:0)
        };return START_NOT_STICKY
    }
    private fun startRequest(){
        pause();aiQueue?.close();aiQueue=null
        val r=JSONObject(File(filesDir,"narration-request.json").readText());val raw=r.getJSONObject("source")
        source=when(raw.optString("engine")){"demo"->Demo.sources.single{it.id==raw.getString("id")};"local"->LocalNovels.source;else->SourceCodec.parse(raw.toString()).singleOrNull()?:error("听书内容源无效")}
        val d=Detail.parse(r.getJSONObject("detail"));require(d.entry.kind==Kind.NOVEL)
        if(detail?.entry?.key!=d.entry.key){timer?.cancel();Narration.update(Narration.state.value.copy(sleepUntil=0))}
        detail=d;line=r.getInt("line");chapter=r.getInt("chapter");require(line in d.lines.indices&&chapter in d.lines[line].chapters.indices)
        mode=r.optString("mode","system");require(mode in setOf("system","ai"))
        aiConfig=if(mode=="ai")AiVoiceSettings(this).load()?:error("请先配对 AI 语音服务")else null
        setText(r.getString("text"),r.optInt("offset"));chapterReady=true;resume()
    }
    private fun foreground(){startForeground(74,notification());isForeground=true}
    private fun notification():Notification {
        fun action(a:String)=PendingIntent.getService(this,a.hashCode(),Intent(this,NarrationService::class.java).setAction(a),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,74,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"narration").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(detail?.entry?.title?:"拾光听书").setContentText(detail?.lines?.getOrNull(line)?.chapters?.getOrNull(chapter)?.title?:"准备听书")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(wants)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,"上一章",action("previous")).build())
            .addAction(Notification.Action.Builder(if(wants)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(wants)"暂停" else "继续",action("toggle")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"结束",action("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    private fun publish(playing:Boolean=Narration.state.value.playing,loading:Boolean=false,message:String=Narration.state.value.message){
        if(destroyed)return
        Narration.update(NarrationState(entry=detail?.entry,chapter=chapter,offset=charOffset,playing=playing,loading=loading,message=message,voices=if(mode=="system")offlineVoices().map{it.name}else emptyList(),voice=if(mode=="system")selectedVoice else "电脑合成音色",rate=rate,sleepUntil=Narration.state.value.sleepUntil,mode=mode,style=aiConfig?.style?:"natural",active=wants,chapterTitle=detail?.lines?.getOrNull(line)?.chapters?.getOrNull(chapter)?.title.orEmpty(),chapterCount=detail?.lines?.getOrNull(line)?.chapters?.size?:0,characters=text.length))
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS).setState(if(playing)PlaybackState.STATE_PLAYING else if(loading)PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playing)1f else 0f).build())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,detail?.entry?.title).putString(MediaMetadata.METADATA_KEY_ARTIST,detail?.lines?.getOrNull(line)?.chapters?.getOrNull(chapter)?.title).build())
        val key="$chapter|$playing|$loading|$message|$wants"
        if(isForeground&&key!=lastNotification){startForeground(74,notification());lastNotification=key}
    }
    private fun setText(value:String,offset:Int){
        text=value;pieces=NovelText.pieces(text);charOffset=offset.coerceIn(0,text.length);cursor=pieces.indexOfFirst{it.end>charOffset}.let{if(it<0)pieces.size else it}
        if(cursor in pieces.indices&&charOffset>pieces[cursor].start){val p=pieces[cursor];pieces=pieces.toMutableList().apply{this[cursor]=SpeechPiece(text.substring(charOffset,p.end),charOffset,p.end)}}
    }
    private fun resume(){
        if(detail==null){fail("请先在小说阅读页开始听书");return}
        if(wants&&Narration.state.value.playing)return
        wants=true
        if(!isForeground)foreground()
        if(!chapterReady){loadChapter();return}
        if(mode=="system"&&!ready){publish(playing=false,loading=true,message="正在连接手机语音");initializeSystemVoice();return}
        if(mode=="system"&&offlineVoices().isEmpty()){fail("未找到离线中文语音，请安装中文语音包");return}
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause("暂时无法取得音频播放权限");return}
        holdWakeLock()
        if(mode=="ai"){
            val queue=aiQueue?:createAiQueue().also{aiQueue=it}
            if(queue.hasContent)queue.resume()else queue.start(text,charOffset,rate)
        }else{setText(text,charOffset);generation++;publish(playing=false,loading=true,message="正在准备离线语音");speakNext()}
    }
    private fun createAiQueue():AiNarrationQueue {
        val config=aiConfig?:error("请先配对 AI 语音服务")
        return AiNarrationQueue(this,AiVoiceClient(config,File(cacheDir,"ai-speech")),scope,
            state={playing,loading,message->if(mode=="ai"&&wants){holdWakeLock();publish(playing,loading,message)}},
            position={offset->if(mode=="ai"){charOffset=offset;save();publish()}},
            finished={if(mode=="ai"&&wants){charOffset=text.length;save();changeChapter(chapter+1)}},
            failed={message->if(mode=="ai")fail(message)},
        )
    }
    private fun holdWakeLock(){if(!lock.isHeld)lock.acquire(15*60_000L)}
    private fun speakNext(){
        if(!wants)return
        if(cursor>=pieces.size){changeChapter(chapter+1);return}
        holdWakeLock()
        if(tts?.speak(pieces[cursor].text,TextToSpeech.QUEUE_FLUSH,Bundle(),"$generation:$cursor")==TextToSpeech.ERROR)fail("手机语音引擎拒绝朗读，请检查中文语音包")
    }
    private fun restartSystemVoice(){val active=wants;generation++;tts?.stop();setText(text,charOffset);publish(playing=false);if(active)resume()}
    private fun seek(requested:Int){
        if(!chapterReady)return
        val active=wants;var target=requested.coerceIn(0,text.length)
        if(target>0&&target<text.length&&Character.isLowSurrogate(text[target])&&Character.isHighSurrogate(text[target-1]))target--
        generation++;tts?.stop();aiQueue?.reset();setText(text,target);save();publish(playing=false,message="已调整朗读位置");if(active)resume()
    }
    private fun pause(message:String="听书已暂停"){
        wants=false;generation++;load?.cancel();tts?.stop();aiQueue?.pause();save();if(lock.isHeld)lock.release();audio.abandonAudioFocusRequest(focus);publish(playing=false,message=message)
    }
    private fun save(){detail?.let{prefs.edit().putString("last-entry",it.entry.key).putInt("chapter",chapter).putInt("offset",charOffset).apply()}}
    private fun fail(message:String){pause(message);if(isForeground){stopForeground(STOP_FOREGROUND_DETACH);isForeground=false}}
    private fun changeChapter(next:Int){
        val d=detail?:return;val chapters=d.lines.getOrNull(line)?.chapters?:return
        if(next !in chapters.indices){pause(if(next>=chapters.size)"全书已听完" else "已经是第一章");return}
        generation++;tts?.stop();aiQueue?.reset();load?.cancel();chapter=next;charOffset=0;text="";chapterReady=false;wants=true;loadChapter()
    }
    private fun loadChapter(){
        load?.cancel();val d=detail?:return;val next=chapter;val run=++generation
        publish(playing=false,loading=true,message="正在加载章节…")
        load=scope.launch{try{
            val body=ContentRepository(this@NarrationService).page(source?:error("内容源已移除"),d.entry,d.lines[line].chapters[next])
            ensureActive();if(run!=generation||!wants)return@launch
            setText(body.text,0);chapterReady=true;save();resume()
        }catch(e:CancellationException){throw e}catch(e:Exception){fail("章节加载失败：${ContentRepository.friendlyError(e)}")}}
    }
    private fun sleep(minutes:Int){
        timer?.cancel();Narration.update(Narration.state.value.copy(sleepUntil=if(minutes>0)System.currentTimeMillis()+minutes*60_000L else 0))
        if(minutes>0)timer=scope.launch{delay(minutes*60_000L);pause("定时结束");Narration.update(Narration.state.value.copy(sleepUntil=0))};publish()
    }
    override fun onDestroy(){
        timer?.cancel();pause("听书已结束");destroyed=true;aiQueue?.close();tts?.shutdown();scope.cancel();unregisterReceiver(noisy);session.release();Narration.update(NarrationState());stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
