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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsAdPlaybackTest {
    private fun playerView(v:View):PlayerView? {
        if(v is PlayerView)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)playerView(v.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun masterPlaylistFiltersInsertedAdBeforeAnySegmentRequestAndPreservesOriginalProgress() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        context.getSharedPreferences("player",0).edit().putBoolean("filterAds",true).apply()
        val requests=CopyOnWriteArrayList<String>();val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val p=request.requestUrl?.encodedPath ?: "/";requests.add(p)
            if(p=="/master.m3u8")return MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS=\"avc1.42c00c,mp4a.40.2\"\nmedia.m3u8\n")
            return runCatching {
                val bytes=instrumentation.context.assets.open("ad-hls/"+p.removePrefix("/")).use{it.readBytes()}
                MockResponse().setHeader("Content-Type",if(p.endsWith("m3u8"))"application/vnd.apple.mpegurl" else "video/mp2t").setBody(Buffer().write(bytes))
            }.getOrElse{MockResponse().setResponseCode(404)}
        }}
        server.start()
        val url=server.url("/master.m3u8").toString()
        val entry=Demo.entries.first().copy(id="ad-fixture-${System.nanoTime()}",sourceId="direct-video",title="广告过滤测试",url=url)
        val ended=CountDownLatch(1);var error:String?=null;var duration=0L
        try {
            val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",entry.json().toString()).putExtra("url",url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<PlayerActivity>(intent).use {scenario->
                PlaybackTestSupport.await(scenario)
                scenario.onActivity {activity->
                    val p=playerView(activity.window.decorView)?.player ?: error("没有播放器")
                    p.volume=0f
                    p.addListener(object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED)ended.countDown()}
                        override fun onPlayerError(e:PlaybackException){error=e.errorCodeName;ended.countDown()}
                    })
                    if(p.playbackState==Player.STATE_ENDED)ended.countDown()
                }
                assertTrue("HLS播放没有完成",ended.await(35,TimeUnit.SECONDS));assertNull(error)
                scenario.onActivity {duration=playerView(it.window.decorView)?.player?.duration ?: 0}
                assertTrue("应移除1秒广告，实际时长=$duration",duration in 7900L..8100L)
                assertFalse("广告分片不应下载",requests.contains("/seg004.ts"))
                assertTrue(requests.contains("/seg000.ts"));assertTrue(requests.contains("/seg008.ts"))
                instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->java.io.File(context.getExternalFilesDir(null),"ad-filter.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }
            LibraryStore(context).use {store->val progress=store.progress().first{it.entry.key==entry.key};assertTrue("进度应映射回原始9秒时间线",progress.offset in 8900L..9100L)}
        }finally{server.shutdown()}
    }
}
