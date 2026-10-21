package app.shiguang.mobile

import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackTestSupport {
    fun view(v:View):PlayerView? {if(v is PlayerView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)view(v.getChildAt(i))?.let{return it};return null}
    fun await(scenario:ActivityScenario<PlayerActivity>,timeout:Long=20000):Player {
        val until=System.currentTimeMillis()+timeout;var player:Player?=null
        while(System.currentTimeMillis()<until) {
            scenario.onActivity{player=view(it.window.decorView)?.player?.takeIf{p->p.currentMediaItem!=null}}
            if(player!=null)return player!!
            Thread.sleep(100)
        }
        error("播放服务未准备好：${PlaybackSession.state.value}")
    }
}
