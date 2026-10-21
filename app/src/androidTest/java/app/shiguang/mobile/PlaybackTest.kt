package app.shiguang.mobile

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackTest {
    private fun playerView(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)playerView(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun nativePlayerRendersVideoAndAdvancesTime() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File(context.cacheDir,"playback-test.mp4")
        instrumentation.context.assets.open("playback-test.mp4").use {input->file.outputStream().use{input.copyTo(it)}}
        val frame=CountDownLatch(1)
        var error:String?=null;var position=0L
        val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",Demo.entries.first().copy(id="native-${System.nanoTime()}").json().toString())
            .putExtra("url",android.net.Uri.fromFile(file).toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PlayerActivity>(intent).use {scenario->
            PlaybackTestSupport.await(scenario)
            scenario.onActivity {activity->
                val player=playerView(activity.window.decorView)?.player ?: error("未找到播放器视图")
                player.volume=0f
                player.addListener(object:Player.Listener {
                    override fun onRenderedFirstFrame(){frame.countDown()}
                    override fun onPlayerError(e:PlaybackException){error=e.errorCodeName;frame.countDown()}
                })
                player.seekTo(0);player.prepare();player.play()
            }
            val rendered=frame.await(30,TimeUnit.SECONDS)
            Thread.sleep(1000)
            scenario.onActivity {position=playerView(it.window.decorView)?.player?.currentPosition ?: 0}
            instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->
                File(context.getExternalFilesDir(null),"player.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            assertTrue("未渲染视频首帧",rendered||PlaybackSession.state.value.rendered);assertNull(error);assertTrue("播放进度没有前进",position>500)
        }
    }
}
