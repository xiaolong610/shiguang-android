package app.shiguang.mobile

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveRulesValidationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private suspend fun decode(video:ResolvedVideo):Long=withContext(Dispatchers.Main){
        val http=DefaultHttpDataSource.Factory().setDefaultRequestProperties(video.headers).setConnectTimeoutMs(12000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true)
        val player=ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http))).build()
        try{
            player.volume=0f;player.setMediaItem(MediaItem.fromUri(video.url));player.prepare();player.play()
            withTimeout(25000){while(!(player.isPlaying&&player.currentPosition>=800)){player.playerError?.let{throw it};delay(120)};player.currentPosition}
        }finally{player.release()}
    }
    @Test fun actualSitesAreCheckedThroughAndroidEnginesAndMediaDecoder()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRules")=="true")
        val raw=instrumentation.context.assets.open("full-rules.json").bufferedReader().use{it.readText()}
        val all=SourceCodec.parse(raw)
        val targets=all.filter{(it.engine=="kazumi"&&!JSONObject(it.rules).optBoolean("deprecated"))||it.name in setOf("rycjapi","360资源","漫畫狗","siluwx","shukuge","800小说网")}
        val rows=JSONArray();val lock=Any();val output=File(context.getExternalFilesDir(null),"live-full-rules-result.json")
        ActivityScenario.launch(MainActivity::class.java).use{
            val slots=Semaphore(2)
            targets.map{s->async(Dispatchers.IO){slots.withPermit{
                val row=JSONObject().put("id",s.id).put("name",s.name).put("kind",s.kind.name.lowercase()).put("engine",s.engine).put("started",System.currentTimeMillis());var stage="search"
                try{withTimeout(85000){
                    val repo=ContentRepository(context);val query=if(s.engine=="kazumi")"海贼"else"同学"
                    val results=repo.searchOne(s,query);row.put("searchCount",results.size);require(results.isNotEmpty()){"搜索没有结果"}
                    stage="detail";val entry=results.first();val detail=repo.detail(s,entry);val chapters=detail.lines.flatMap{it.chapters};row.put("chapterCount",chapters.size);require(chapters.isNotEmpty()){"目录为空"}
                    stage="content"
                    when(s.kind){
                        Kind.NOVEL->{val text=repo.page(s,entry,chapters.first()).text;require(text.length>=50){"正文不足50字，可能是提示页面"};row.put("textCharacters",text.length).put("status","Android小说阅读通过")}
                        Kind.COMIC->{val page=repo.page(s,entry,chapters.first());require(page.images.isNotEmpty());val image=page.images.first();val response=repo.client.newCall(okhttp3.Request.Builder().url(image).apply{repo.headers(s).forEach{(k,v)->header(k,v)}}.build()).execute();response.use{r->require(r.isSuccessful){"图片HTTP ${r.code}"};val head=r.body?.byteStream()?.use{val b=ByteArray(16);val n=it.read(b);if(n>0)b.copyOf(n)else byteArrayOf()}?:byteArrayOf();require(head.size>3&&((head[0]==0xff.toByte()&&head[1]==0xd8.toByte())||head.copyOfRange(0,4).contentEquals(byteArrayOf(0x89.toByte(),80,78,71))||String(head.take(4).toByteArray())in setOf("RIFF","GIF8"))){"图片响应不是可识别的图像"}};row.put("imageCount",page.images.size).put("status","Android漫画图片读取通过")}
                        Kind.VIDEO->{stage="resolve";val video=WebVideoResolver(context,repo).resolve(s,chapters.first().url);stage="decode";val position=decode(video);row.put("playedMs",position).put("status","Android解析与播放通过")}
                    }
                }}catch(e:Exception){row.put("status","Android检测未通过").put("failedStage",stage).put("error",ContentRepository.friendlyError(e))}
                row.put("finished",System.currentTimeMillis());synchronized(lock){rows.put(row);output.writeText(JSONObject().put("tested",rows.length()).put("planned",targets.size).put("rows",rows).toString(2))}
            }}}.awaitAll()
        }
        assertTrue("没有一个Android站点完成内容读取或播放",(0 until rows.length()).any{rows.getJSONObject(it).optString("status").endsWith("通过")&&rows.getJSONObject(it).optString("status")!="Android检测未通过"})
    }
}
