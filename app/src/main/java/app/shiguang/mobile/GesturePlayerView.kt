package app.shiguang.mobile

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.media3.ui.PlayerView

/** Keep native controls and the seek bar untouched; gestures use the central picture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GesturePlayerView(context:Context):PlayerView(context) {
    var gesturesEnabled=true
    var onBoost:(Boolean)->Unit={}
    private var centralGesture=false
    private var holding=false
    private val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {if(isControllerFullyVisible)hideController()else showController();performClick();return true}
        override fun onDoubleTap(e:MotionEvent):Boolean {player?.let{if(it.playWhenReady)it.pause()else it.play()};showController();return true}
        override fun onLongPress(e:MotionEvent){if(player?.isPlaying==true){holding=true;hideController();onBoost(true)}}
    })
    fun releaseBoost(){if(holding){holding=false;onBoost(false)}}
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!gesturesEnabled){releaseBoost();return super.dispatchTouchEvent(event)}
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            val density=resources.displayMetrics.density
            val point=IntArray(2);getLocationOnScreen(point)
            val nativeButton=if(isControllerFullyVisible)listOf(androidx.media3.ui.R.id.exo_play_pause,androidx.media3.ui.R.id.exo_prev,androidx.media3.ui.R.id.exo_next,androidx.media3.ui.R.id.exo_ffwd,androidx.media3.ui.R.id.exo_rew).any{id->
                findViewById<android.view.View>(id)?.let{view->val rect=android.graphics.Rect();view.getGlobalVisibleRect(rect)&&rect.contains((point[0]+event.x).toInt(),(point[1]+event.y).toInt())}?:false
            }else false
            centralGesture=!nativeButton&&event.y>48*density&&event.y<height-80*density
        }
        if(!centralGesture)return super.dispatchTouchEvent(event)
        detector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){releaseBoost();centralGesture=false}
        return true
    }
    override fun onDetachedFromWindow(){releaseBoost();super.onDetachedFromWindow()}
}
