package app.shiguang.mobile

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private object Restart010 {
    val ins get()=InstrumentationRegistry.getInstrumentation();val context get()=ins.targetContext
    val file get()=File(context.filesDir,".download-restart010.json")
    fun main(block:()->Unit)=ins.runOnMainSync(block)
    fun waitFor(condition:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue("下载状态没有达到预期：${VideoDownloads.items.value}",condition())}
    fun sample()=ins.context.assets.open("playback-test.mp4").use{it.readBytes()}
    fun large():ByteArray {val sample=sample();return ByteArray(sample.size*32){sample[it%sample.size]}}
}

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadPrepare010Test {
    @Test fun prepareCompletedAndPausedDownloadsForProcessRestart()=runBlocking {
        val server=MockWebServer();val sample=Restart010.sample();val large=Restart010.large()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(if(request.path!!.startsWith("/partial"))large else sample)).apply{if(request.path!!.startsWith("/partial"))throttleBody(32768,50,TimeUnit.MILLISECONDS)}}
        server.start();val context=Restart010.context
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            try{
                Restart010.main{VideoDownloads.wifi(context,false)}
                if(Restart010.file.exists()){
                    val old=JSONObject(Restart010.file.readText());val oldIds=listOf(old.getString("full"),old.getString("partial"));Restart010.main{oldIds.forEach{VideoDownloads.remove(context,it)}};Restart010.waitFor{VideoDownloads.items.value.none{it.id in oldIds}};Restart010.file.delete()
                }
                val complete=Entry("restart010-full-${System.nanoTime()}","direct-video","重启后离线播放",Kind.VIDEO,server.url("/complete.mp4").toString())
                VideoDownloads.add(context,null,complete,Chapter("完整下载验证",complete.url));val fullId=SourceCodec.stableId(complete.key+"|"+complete.url)
                Restart010.waitFor{VideoDownloads.items.value.any{it.id==fullId&&it.state==Download.STATE_COMPLETED}}
                val partial=complete.copy(id="restart010-partial-${System.nanoTime()}",title="重启后断点续传",url=server.url("/partial.mp4").toString())
                VideoDownloads.add(context,null,partial,Chapter("断点保存验证",partial.url));val partialId=SourceCodec.stableId(partial.key+"|"+partial.url)
                Restart010.waitFor{VideoDownloads.items.value.any{it.id==partialId&&it.state==Download.STATE_DOWNLOADING&&it.bytes>32768}}
                Restart010.main{VideoDownloads.pause(context,partialId)}
                Restart010.waitFor{VideoDownloads.items.value.any{it.id==partialId&&it.stopped}}
                Restart010.waitFor{var idle=false;Restart010.main{idle=VideoDownloads.manager(context).isIdle};idle}
                val bytes=withContext(Dispatchers.IO){VideoDownloads.cache(context).getCachedBytes(partial.url,0,androidx.media3.common.C.LENGTH_UNSET.toLong())};assertTrue(bytes>0)
                Restart010.file.writeText(JSONObject().put("full",fullId).put("partial",partialId).put("bytes",bytes).put("port",server.port).put("entry",complete.json()).put("partialEntry",partial.json()).toString())
                scenario.onActivity{it.setContent{ShiguangTheme{StorageSheet{}}}};Thread.sleep(400)
                Restart010.ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"reliability010-downloads.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            }finally{server.shutdown()}
        }
    }
}

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadVerify010Test {
    @Test fun verifyDownloadsAfterActualProcessRestart()=runBlocking {
        val context=Restart010.context;val data=JSONObject(Restart010.file.readText());val fullId=data.getString("full");val partialId=data.getString("partial")
        ActivityScenario.launch(MainActivity::class.java).use{
            Restart010.main{VideoDownloads.manager(context);VideoDownloads.wifi(context,false)}
            Restart010.waitFor{VideoDownloads.items.value.any{it.id==fullId&&it.state==Download.STATE_COMPLETED}&&VideoDownloads.items.value.any{it.id==partialId&&it.stopped}}
            assertTrue(VideoDownloads.items.value.first{it.id==partialId}.bytes>=data.getLong("bytes"))
            val item=VideoDownloads.items.value.first{it.id==fullId}
            val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",item.entry.json().toString()).putExtra("url",item.chapter.url).putExtra("offlineId",fullId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<PlayerActivity>(intent).use{scenario->
                PlaybackTestSupport.await(scenario);Restart010.waitFor{PlaybackSession.state.value.rendered||PlaybackSession.state.value.error!=null};assertNull(PlaybackSession.state.value.error);assertTrue(PlaybackSession.state.value.rendered)
            }
            context.stopService(Intent(context,PlaybackService::class.java))
            val server=MockWebServer();val large=Restart010.large();val ranges=CopyOnWriteArrayList<Long>()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                val at=request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()?:0;ranges+=at
                return MockResponse().setHeader("Content-Type","video/mp4").setHeader("Accept-Ranges","bytes").setBody(Buffer().write(large,at.toInt(),large.size-at.toInt())).apply{if(at>0)setResponseCode(206).setHeader("Content-Range","bytes $at-${large.lastIndex}/${large.size}")}
            }}
            server.start(data.getInt("port"))
            try{Restart010.main{VideoDownloads.resume(context,partialId)};Restart010.waitFor{VideoDownloads.items.value.any{it.id==partialId&&it.state==Download.STATE_COMPLETED}};assertTrue(ranges.any{it>0})}
            finally{
                server.shutdown();Restart010.main{VideoDownloads.remove(context,fullId);VideoDownloads.remove(context,partialId);VideoDownloads.wifi(context,true)}
                Restart010.waitFor{VideoDownloads.items.value.none{it.id==fullId||it.id==partialId}}
                LibraryStore(context).use{db->listOf("entry","partialEntry").forEach{key->val e=Entry.parse(data.getJSONObject(key));db.writableDatabase.delete("progress","id=?",arrayOf(e.key))}}
                Restart010.file.delete()
            }
        }
    }
}
