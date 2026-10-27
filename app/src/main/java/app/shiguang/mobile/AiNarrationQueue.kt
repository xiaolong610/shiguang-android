package app.shiguang.mobile

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Two-segment lookahead: synthesize the next short segment while the current WAV is playing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AiNarrationQueue(
    context:Context,
    private val client:AiVoiceClient,
    private val scope:CoroutineScope,
    private val state:(Boolean,Boolean,String)->Unit,
    private val position:(Int)->Unit,
    private val finished:()->Unit,
    private val failed:(String)->Unit,
) {
    private val player=ExoPlayer.Builder(context).build().apply{
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),false)
    }
    private val current=MutableStateFlow(0)
    private var pieces=emptyList<SpeechPiece>()
    private var worker:Job?=null
    private var wants=false
    private var epoch=0
    private var pendingError:String?=null
    private var completed=false
    val hasContent get()=pieces.isNotEmpty()
    init {
        player.addListener(object:Player.Listener{
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){
                if(item==null||pieces.isEmpty())return
                current.value=player.currentMediaItemIndex
                pieces.getOrNull(player.currentMediaItemIndex)?.let{position(it.start)}
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(wants&&isPlaying)state(true,false,tr(R.string.ui_0003))}
            override fun onPlaybackStateChanged(playbackState:Int){
                if(!wants||pieces.isEmpty())return
                when(playbackState){
                    Player.STATE_BUFFERING->state(false,true,tr(R.string.ui_0004))
                    Player.STATE_ENDED->{
                        val index=player.currentMediaItemIndex
                        if(index==pieces.lastIndex&&player.mediaItemCount==pieces.size){
                            if(!completed){completed=true;wants=false;position(pieces.last().end);finished()}
                        } else if(pendingError!=null){wants=false;failed(pendingError!!)}
                        else state(false,true,tr(R.string.ui_0005))
                    }
                }
            }
            override fun onPlayerError(error:PlaybackException){
                if(!wants)return
                wants=false;worker?.cancel()
                player.currentMediaItem?.localConfiguration?.uri?.path?.let{path->java.io.File(path).delete()}
                failed(tr(R.string.ui_0006))
            }
        })
    }
    fun start(text:String,offset:Int,rate:Float){
        reset()
        pieces=NovelText.pieces(text,80).filter{it.end>offset}.mapIndexed{i,p->if(i==0&&offset>p.start)SpeechPiece(text.substring(offset,p.end),offset,p.end)else p}
        player.playbackParameters=PlaybackParameters(rate)
        if(pieces.isEmpty()){finished();return}
        resume()
    }
    fun resume(){
        if(pieces.isEmpty())return
        wants=true;completed=false;pendingError=null
        if(player.playerError!=null){val offset=pieces.getOrNull(player.currentMediaItemIndex)?.start?:pieces.first().start;val remaining=pieces.filter{it.end>offset};reset();pieces=remaining;wants=true}
        if(player.mediaItemCount>0){player.playWhenReady=true;if(player.playbackState==Player.STATE_IDLE)player.prepare()}
        state(player.isPlaying,!player.isPlaying,if(player.isPlaying)tr(R.string.ui_0003)else tr(R.string.ui_0004))
        fill()
    }
    fun pause(){wants=false;epoch++;worker?.cancel();worker=null;player.pause()}
    fun rate(rate:Float){player.playbackParameters=PlaybackParameters(rate)}
    fun reset(){pause();pieces=emptyList();player.stop();player.clearMediaItems();current.value=0;completed=false;pendingError=null}
    fun close(){reset();player.release()}
    private fun fill(){
        worker?.cancel();val run=++epoch
        worker=scope.launch{
            try{
                val health=client.health()
                require(health.optJSONArray("styles")?.let{a->(0 until a.length()).any{a.optString(it)==client.configuredStyle}}==true){tr(R.string.ui_0009)}
                require(health.optJSONArray("voices")?.let{a->(0 until a.length()).any{a.optString(it)==client.configuredVoice}}==true){tr(R.string.ui_0010)}
                var next=player.mediaItemCount
                while(isActive&&wants&&run==epoch&&next<pieces.size){
                    current.first{next<=it+1};ensureActive()
                    val file=withTimeout(125_000){
                        var result:java.io.File?=null;var attempts=0
                        while(result==null){try{result=client.synthesize(pieces[next].text)}catch(e:AiVoiceBusyException){if(++attempts>=5)throw e;delay((1L shl attempts.coerceAtMost(3))*500)}}
                        result
                    }
                    ensureActive();if(!wants||run!=epoch)return@launch
                    val ended=player.playbackState==Player.STATE_ENDED
                    player.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    if(next==0||ended){if(ended)player.seekTo(next,0);player.prepare();player.playWhenReady=true}
                    next++
                }
            }catch(e:TimeoutCancellationException){reportFailure(run,tr(R.string.ui_0011))}
            catch(e:CancellationException){throw e}
            catch(e:Exception){reportFailure(run,ContentRepository.friendlyError(e))}
        }
    }
    private fun reportFailure(run:Int,message:String){
        if(run!=epoch||!wants)return
        pendingError=message
        if(player.mediaItemCount==0||player.playbackState==Player.STATE_ENDED){wants=false;failed(message)}
    }
}
