package app.shiguang.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDownload010Test {
    private val ins=InstrumentationRegistry.getInstrumentation();private val context=ins.targetContext
    private val ids=mutableListOf<String>();private val entries=mutableListOf<Entry>()
    private fun main(action:()->Unit)=ins.runOnMainSync(action)
    private fun waitFor(timeout:Long=30000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(80)};assertTrue("等待下载状态超时：${VideoDownloads.items.value}",condition())}
    private fun entry(url:String)=Entry("offline010-${System.nanoTime()}","direct-video","离线下载验证",Kind.VIDEO,url).also{entries+=it}
    @Before fun start(){main{VideoDownloads.wifi(context,false)}}
    @After fun clean(){context.stopService(Intent(context,PlaybackService::class.java));Thread.sleep(200);main{ids.forEach{VideoDownloads.remove(context,it)};VideoDownloads.wifi(context,true)};waitFor{VideoDownloads.items.value.none{it.id in ids}};LibraryStore(context).use{db->entries.forEach{db.writableDatabase.delete("progress","id=?",arrayOf(it.key))}}}
    private fun renderOffline(item:OfflineVideo,throughEnd:Boolean=false){
        val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",item.entry.json().toString()).putExtra("url",item.chapter.url).putExtra("title",item.chapter.title).putExtra("offlineId",item.id).putExtra("chapter",item.chapterIndex).putExtra("line",item.line).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PlayerActivity>(intent).use{scenario->
            val player=PlaybackTestSupport.await(scenario);scenario.onActivity{player.volume=0f}
            waitFor(20000){PlaybackSession.state.value.offlineId==item.id&&(PlaybackSession.state.value.rendered||PlaybackSession.state.value.error!=null)}
            assertNull(PlaybackSession.state.value.error);assertTrue(PlaybackSession.state.value.rendered)
            ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"reliability010-offline-player.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            if(throughEnd){var ended=false;scenario.onActivity{player.addListener(object:Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED)ended=true}});if(player.playbackState==Player.STATE_ENDED)ended=true};waitFor(20000){ended||PlaybackSession.state.value.error!=null};assertNull(PlaybackSession.state.value.error);assertTrue(ended)}
            scenario.onActivity{player.pause()}
        }
    }
    @Test fun mp4DownloadSurvivesCacheCleanupAndOfflinePlaybackRetainsOriginalEpisode()=runBlocking {
        val server=MockWebServer();val bytes=ins.context.assets.open("playback-test.mp4").use{it.readBytes()}
        server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes)));server.start()
        val e=entry(server.url("/movie.mp4").toString())
        ActivityScenario.launch(MainActivity::class.java).use{
            try{
                VideoDownloads.add(context,null,e,Chapter("第五集",e.url),4,1)
                val id=SourceCodec.stableId(e.key+"|"+e.url);ids+=id
                waitFor{VideoDownloads.items.value.any{it.id==id&&it.state==Download.STATE_COMPLETED}}
                val item=VideoDownloads.items.value.first{it.id==id};assertEquals(bytes.size.toLong(),item.bytes)
                server.shutdown();AppStorage.clear(context,"images");AppStorage.clear(context,"streaming");AppStorage.clear(context,"memory")
                assertEquals(e.url,VideoDownloads.completed(context,id).uri.toString())
                renderOffline(item);context.stopService(Intent(context,PlaybackService::class.java));delay(300)
                val p=LibraryStore(context).use{it.progressFor(e.key)};assertNotNull(p);assertEquals(4,p!!.chapter);assertEquals(1,p.line)
            }finally{runCatching{server.shutdown()}}
        }
    }
    @Test fun vodHlsMasterIsDownloadedAndRendersAfterServerStops()=runBlocking {
        val server=MockWebServer();val paths=CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath;paths+=path
            if(path=="/master.m3u8")return MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=320x180,CODECS=\"avc1.42c00c,mp4a.40.2\"\nmedia-low.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=640x360,CODECS=\"avc1.42c00c,mp4a.40.2\"\nmedia.m3u8\n")
            return runCatching{val bytes=ins.context.assets.open("ad-hls/"+path.removePrefix("/").replace("media-low.m3u8","media.m3u8")).use{it.readBytes()};MockResponse().setHeader("Content-Type",if(path.endsWith("m3u8"))"application/vnd.apple.mpegurl"else"video/mp2t").setBody(Buffer().write(bytes))}.getOrElse{MockResponse().setResponseCode(404)}
        }};server.start();val e=entry(server.url("/master.m3u8").toString())
        ActivityScenario.launch(MainActivity::class.java).use{
            try{VideoDownloads.add(context,null,e,Chapter("HLS完整下载",e.url));val id=SourceCodec.stableId(e.key+"|"+e.url);ids+=id
                waitFor{VideoDownloads.items.value.any{it.id==id&&it.state==Download.STATE_COMPLETED}}
                assertTrue(paths.any{it.endsWith(".ts")});assertEquals(1,VideoDownloads.completed(context,id).streamKeys.count{it.groupIndex==0});val item=VideoDownloads.items.value.first{it.id==id};server.shutdown();renderOffline(item,throughEnd=true)
            }finally{runCatching{server.shutdown()}}
        }
    }
    @Test fun pauseAndResumeUsePersistedPartialBytesAndRangeRequests()=runBlocking {
        val server=MockWebServer();val sample=ins.context.assets.open("playback-test.mp4").use{it.readBytes()};val bytes=ByteArray(sample.size*32){sample[it%sample.size]};val offsets=CopyOnWriteArrayList<Long>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            val offset=request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()?:0L;offsets+=offset
            val response=MockResponse().setHeader("Accept-Ranges","bytes").setHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes,offset.toInt(),bytes.size-offset.toInt())).throttleBody(32768,50,TimeUnit.MILLISECONDS)
            return if(offset>0)response.setResponseCode(206).setHeader("Content-Range","bytes $offset-${bytes.lastIndex}/${bytes.size}")else response
        }};server.start();val e=entry(server.url("/resume.mp4").toString())
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            try{VideoDownloads.add(context,null,e,Chapter("断点验证",e.url));val id=SourceCodec.stableId(e.key+"|"+e.url);ids+=id
                waitFor{VideoDownloads.items.value.any{it.id==id&&it.bytes>32768&&it.state==Download.STATE_DOWNLOADING}}
                val beforeBackground=VideoDownloads.items.value.first{it.id==id}.bytes
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                waitFor{VideoDownloads.items.value.any{it.id==id&&it.bytes>beforeBackground+32768}}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                main{VideoDownloads.pause(context,id)};waitFor{VideoDownloads.items.value.any{it.id==id&&it.stopped}}
                val paused=VideoDownloads.items.value.first{it.id==id}.bytes;assertTrue(paused>0)
                main{VideoDownloads.resume(context,id)};waitFor(40000){VideoDownloads.items.value.any{it.id==id&&it.state==Download.STATE_COMPLETED}}
                assertTrue("没有沿用断点",offsets.any{it>0});assertEquals(bytes.size.toLong(),VideoDownloads.items.value.first{it.id==id}.bytes)
            }finally{server.shutdown()}
        }
    }
}
