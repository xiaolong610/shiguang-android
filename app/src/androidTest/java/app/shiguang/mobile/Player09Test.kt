package app.shiguang.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Player09Test {
    private val ins=InstrumentationRegistry.getInstrumentation();private val context=ins.targetContext
    @After fun stop(){context.stopService(Intent(context,PlaybackService::class.java));Thread.sleep(200)}
    private fun launch():Intent {
        context.getSharedPreferences("player",0).edit().putBoolean("autoPip",false).putBoolean("backgroundPlayback",true).putFloat("speed",1.25f).commit()
        val file=File(context.cacheDir,"upgrade09-player.mp4");ins.context.assets.open("playback-test.mp4").use{input->file.outputStream().use{input.copyTo(it)}}
        return Intent(context,PlayerActivity::class.java).putExtra("entry",Demo.entries.first().copy(id="player09-${System.nanoTime()}").json().toString()).putExtra("url",android.net.Uri.fromFile(file).toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    private fun waitFor(message:String,predicate:()->Boolean){val end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){if(predicate())return;Thread.sleep(50)};assertTrue(message,predicate())}
    private fun touch(scenario:ActivityScenario<PlayerActivity>,action:Int,down:Long){scenario.onActivity{a->val v=PlaybackTestSupport.view(a.window.decorView)!!;val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,v.width*.5f,v.height*.5f,0);try{v.dispatchTouchEvent(event)}finally{event.recycle()}}}
    @Test fun centralDoubleTapPausesAndResumesAndHoldingRestoresOriginalSpeed(){
        ActivityScenario.launch<PlayerActivity>(launch()).use{scenario->
            val player=PlaybackTestSupport.await(scenario)
            scenario.onActivity{player.repeatMode=Player.REPEAT_MODE_ONE;player.play();PlaybackTestSupport.view(it.window.decorView)!!.hideController()}
            waitFor("未开始播放"){PlaybackSession.state.value.playing}
            fun doubleTap(){val first=SystemClock.uptimeMillis();touch(scenario,MotionEvent.ACTION_DOWN,first);Thread.sleep(40);touch(scenario,MotionEvent.ACTION_UP,first);Thread.sleep(80);val second=SystemClock.uptimeMillis();touch(scenario,MotionEvent.ACTION_DOWN,second);Thread.sleep(40);touch(scenario,MotionEvent.ACTION_UP,second)}
            doubleTap();waitFor("双击未暂停"){!PlaybackSession.state.value.playing}
            scenario.onActivity{PlaybackTestSupport.view(it.window.decorView)!!.hideController()};Thread.sleep(350)
            doubleTap();waitFor("双击未恢复播放"){PlaybackSession.state.value.playing}
            Thread.sleep(350);scenario.onActivity{PlaybackTestSupport.view(it.window.decorView)!!.hideController()}
            val down=SystemClock.uptimeMillis();touch(scenario,MotionEvent.ACTION_DOWN,down);Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L)
            waitFor("长按未进入临时倍速"){PlaybackSession.state.value.boosting&&PlaybackSession.state.value.speed>=2f}
            assertEquals(1.25f,context.getSharedPreferences("player",0).getFloat("speed",0f),.01f)
            touch(scenario,MotionEvent.ACTION_UP,down);waitFor("松手未恢复原倍速"){!PlaybackSession.state.value.boosting&&PlaybackSession.state.value.speed==1.25f}
            val again=SystemClock.uptimeMillis();touch(scenario,MotionEvent.ACTION_DOWN,again);Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L)
            waitFor("再次长按未加速"){PlaybackSession.state.value.boosting};scenario.moveToState(Lifecycle.State.CREATED)
            waitFor("切后台后临时倍速未恢复"){!PlaybackSession.state.value.boosting&&PlaybackSession.state.value.speed==1.25f}
        }
    }
    @Test fun fullscreenBackReturnsToWindowWithoutReloadingAndSecondBackClosesPage(){
        ActivityScenario.launch<PlayerActivity>(launch()).use{scenario->
            val player=PlaybackTestSupport.await(scenario);val id=PlaybackSession.state.value.requestId
            waitFor("未渲染首帧"){PlaybackSession.state.value.rendered}
            scenario.onActivity{player.repeatMode=Player.REPEAT_MODE_ONE;player.seekTo(2000);player.pause();it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE}
            var portrait=false;var destroyed=false
            waitFor("未进入横屏"){var landscape=false;scenario.onActivity{landscape=it.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE};landscape}
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            waitFor("返回未回到竖屏窗口"){scenario.onActivity{portrait=it.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT;destroyed=it.isFinishing};portrait}
            assertFalse(destroyed);assertEquals(id,PlaybackSession.state.value.requestId)
            scenario.onActivity{assertEquals(2000L,player.currentPosition);assertFalse(player.playWhenReady)}
            Thread.sleep(350);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"upgrade09-03-player-window.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            waitFor("第二次返回没有离开播放器"){scenario.state==Lifecycle.State.DESTROYED}
        }
    }
}
