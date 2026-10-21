package app.shiguang.mobile

import android.app.PictureInPictureParams
import android.content.Intent
import android.util.Rational
import android.webkit.CookieManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlineFeaturesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @After fun cleanup(){context.stopService(Intent(context,PlaybackService::class.java));Thread.sleep(200)}
    private fun intent():Intent {
        context.getSharedPreferences("player",0).edit().putBoolean("backgroundPlayback",true).putBoolean("autoPip",false).apply()
        val file=File(context.cacheDir,"online-test.mp4");instrumentation.context.assets.open("playback-test.mp4").use{input->file.outputStream().use{input.copyTo(it)}}
        return Intent(context,PlayerActivity::class.java).putExtra("entry",Demo.entries.first().copy(id="online-${System.nanoTime()}").json().toString()).putExtra("url",android.net.Uri.fromFile(file).toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    @Test fun serviceKeepsPlaybackWhenActivityIsStoppedAndRecreationReconnects() {
        ActivityScenario.launch<PlayerActivity>(intent()).use{scenario->
            val player=PlaybackTestSupport.await(scenario);var before=0L
            scenario.onActivity{player.repeatMode=Player.REPEAT_MODE_ONE;player.seekTo(0);player.play()}
            Thread.sleep(900);scenario.onActivity{before=player.currentPosition}
            scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(1400)
            var after=0L;instrumentation.runOnMainSync{after=player.currentPosition;assertTrue(player.isPlaying)}
            assertTrue("切后台后没有继续前进：$before->$after",after-before>700)
            scenario.moveToState(Lifecycle.State.RESUMED);scenario.recreate();PlaybackTestSupport.await(scenario)
            scenario.onActivity{val p=PlaybackTestSupport.view(it.window.decorView)!!.player!!;assertTrue(p.currentPosition>=after);assertTrue(p.isPlaying)}
        }
    }
    @Test fun systemPictureInPictureActuallyEntersAndReturns() {
        ActivityScenario.launch<PlayerActivity>(intent()).use{scenario->
            PlaybackTestSupport.await(scenario);Thread.sleep(500)
            var activity:PlayerActivity?=null;var entered=false
            scenario.onActivity{activity=it;entered=it.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())}
            assertTrue("系统拒绝画中画",entered);Thread.sleep(1000)
            instrumentation.runOnMainSync{assertTrue("未进入画中画状态",activity!!.isInPictureInPictureMode)}
            instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"pip.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            context.startActivity(Intent(context,PlayerActivity::class.java).putExtra("resume",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            Thread.sleep(700);instrumentation.runOnMainSync{assertFalse(activity!!.isInPictureInPictureMode)}
        }
    }
    @Test fun nestedDynamicPageFindsExtensionlessHlsAndRejectsFakeMedia()=runBlocking {
        val server=MockWebServer();val requests=java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            requests.add(request)
            return when(request.requestUrl?.encodedPath) {
                "/watch"->MockResponse().setHeader("Content-Type","text/html").setBody("<html><body><script>setTimeout(()=>{let f=document.createElement('iframe');f.src='/frame';document.body.appendChild(f)},100)</script></body></html>")
                "/frame"->MockResponse().setHeader("Content-Type","text/html").setBody("<script>setTimeout(()=>{ShiguangMedia.found(location.origin+'/fake.mp4',location.href,'video/mp4');fetch('/media?id=1').then(r=>r.text()).then(t=>console.log(t))},300)</script>")
                "/fake.mp4"->MockResponse().setHeader("Content-Type","text/html").setBody("<html>not a video</html>")
                "/media"->MockResponse().setHeader("Content-Type","text/plain").setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n")
                else->MockResponse().setResponseCode(404)
            }
        }};server.start()
        try {
            val cookieUrl=server.url("/").toString()
            instrumentation.runOnMainSync{CookieManager.getInstance().setCookie(cookieUrl,"sgtest=keep; Path=/")}
            val result=WebVideoResolver(context).resolve(null,server.url("/watch").toString())
            assertEquals(server.url("/media?id=1").toString(),result.url);assertEquals("application/x-mpegURL",result.mime)
            assertTrue(result.headers["Referer"].orEmpty().endsWith("/frame"));assertTrue(result.headers["Cookie"].orEmpty().contains("sgtest=keep"));assertTrue(requests.any{it.path=="/fake.mp4"})
        }finally{server.shutdown()}
    }
    @Test fun subscriptionUpdatesAreAtomicPreserveDisabledAndDoNotResurrectDeletedSources()=runBlocking {
        val server=MockWebServer();server.start();val url=server.url("/sources.json").toString();val id=SourceCodec.stableId("subscription|$url")
        fun body(version:String)="""[{"name":"订阅测试","api":"${server.url("/api")}","rules":{"version":"$version"}}]"""
        try {
            server.enqueue(MockResponse().setHeader("ETag","v1").setBody(body("1")));val manager=SubscriptionManager(context);assertFalse(manager.add(url).startsWith("刷新失败"))
            val source=LibraryStore(context).use{store->store.sources().first{it.url==server.url("/api").toString()}.also{store.putSource(it.copy(enabled=false))}}
            server.enqueue(MockResponse().setHeader("ETag","v2").setBody(body("2")));manager.refresh(id)
            LibraryStore(context).use{store->val updated=store.sources().first{it.id==source.id};assertFalse(updated.enabled);assertEquals("2",JSONObject(updated.rules).getString("version"))}
            server.enqueue(MockResponse().setResponseCode(304));assertEquals("没有更新",manager.refresh(id))
            LibraryStore(context).use{store->assertFalse(store.sources().first{it.id==source.id}.enabled)}
            server.enqueue(MockResponse().setBody("not JSON"));assertTrue(manager.refresh(id).startsWith("刷新失败"))
            LibraryStore(context).use{store->assertEquals("2",JSONObject(store.sources().first{it.id==source.id}.rules).getString("version"))}
            manager.restore(id)
            LibraryStore(context).use{store->assertEquals("1",JSONObject(store.sources().first{it.id==source.id}.rules).getString("version"));assertFalse(store.subscriptions().first{it.id==id}.enabled);assertFalse(store.sources().first{it.id==source.id}.enabled);store.deleteSource(source.id)}
            server.enqueue(MockResponse().setBody(body("3")));manager.refresh(id)
            LibraryStore(context).use{store->assertTrue(store.sources().none{it.id==source.id})}
        }finally{LibraryStore(context).use{store->store.subscriptionLinks(id).forEach{store.deleteSource(it.sourceId)};store.deleteSubscription(id)};server.shutdown()}
    }
    @Test fun xpathAdapterWorksOnAndroidRuntime() {
        val r=JSONObject().put("api","5").put("name","设备XPath测试").put("baseURL","https://anime.example/").put("searchURL","https://anime.example/s?q=@keyword").put("searchList","//li").put("searchName","//a").put("searchResult","//a").put("chapterRoads","//ul").put("chapterResult","//li/a")
        val s=SourceCodec.parse(r.toString()).single()
        val entries=KazumiRules.search(s,"<ul><li><a href='/1'>甲</a></li><li><a href='/2'>乙</a></li></ul>",s.url)
        assertEquals(listOf("甲","乙"),entries.map{it.title})
        assertEquals(2,KazumiRules.detail(s,entries.first(),"<ul><li><a href='/p/1'>01</a></li><li><a href='/p/2'>02</a></li></ul>").lines.single().chapters.size)
    }
    @Test fun dynamicOnlinePlaybackAutomaticallyResolvesNextEpisodeAndFiltersAds() {
        val server=MockWebServer();val visited=java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val p=request.requestUrl!!.encodedPath;visited.add(p)
            if(p.startsWith("/watch/"))return MockResponse().setHeader("Content-Type","text/html").setBody("<script>setTimeout(()=>{fetch('/media').then(r=>r.text()).then(t=>console.log(t))},150)</script>")
            val name=if(p=="/media")"media.m3u8" else p.substringAfterLast('/')
            return runCatching {val bytes=instrumentation.context.assets.open("ad-hls/$name").use{it.readBytes()};MockResponse().setHeader("Content-Type",if(p=="/media")"text/plain" else "video/mp2t").setBody(okio.Buffer().write(bytes))}.getOrElse{MockResponse().setResponseCode(404)}
        }};server.start()
        try {
            val entry=Demo.entries.first().copy(id="dynamic-${System.nanoTime()}",sourceId="direct-video",title="动态在线播放测试")
            val pages=org.json.JSONArray().put(JSONObject().put("title","第1集").put("url",server.url("/watch/1"))).put(JSONObject().put("title","第2集").put("url",server.url("/watch/2")))
            context.getSharedPreferences("player",0).edit().putBoolean("filterAds",true).putBoolean("autoPip",false).apply()
            val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",entry.json().toString()).putExtra("playlist",pages.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<PlayerActivity>(intent).use{scenario->
                PlaybackTestSupport.await(scenario,30000)
                val until=System.currentTimeMillis()+30000
                while(System.currentTimeMillis()<until){val info=PlaybackSession.state.value;if(info.error!=null)error(info.error!!);if(info.chapter==1&&info.rendered)break;Thread.sleep(150)}
                val info=PlaybackSession.state.value;assertEquals(1,info.chapter);assertTrue("第二集未渲染",info.rendered)
                assertTrue(visited.contains("/watch/1"));assertTrue(visited.contains("/watch/2"));assertFalse("动态播放不应下载广告",visited.contains("/seg004.ts"))
                Thread.sleep(2100)
                LibraryStore(context).use{store->val progress=store.progress().first{it.entry.key==entry.key};assertEquals(1,progress.chapter)}
            }
        }finally{server.shutdown()}
    }
    @Test fun workManagerRefreshesDueSubscriptionsThroughTheRealWorker() {
        val server=MockWebServer();server.start();val url=server.url("/worker-sources.json").toString();val id=SourceCodec.stableId("subscription|$url")
        val source=SourceCodec.parse("""{"name":"后台刷新测试","api":"${server.url("/api")}"}""").single()
        val manager=androidx.work.WorkManager.getInstance(context)
        val work=androidx.work.OneTimeWorkRequestBuilder<SubscriptionWorker>().build()
        try {
            server.enqueue(MockResponse().setBody("""[{"name":"后台刷新测试","api":"${server.url("/api")}"}]"""))
            LibraryStore(context).use{it.putSubscription(SourceSubscription(id,"后台刷新测试",url))}
            manager.enqueue(work).result.get(10,java.util.concurrent.TimeUnit.SECONDS)
            val until=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<until){if(manager.getWorkInfoById(work.id).get()?.state?.isFinished==true)break;Thread.sleep(200)}
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(work.id).get()!!.state)
            LibraryStore(context).use{store->assertTrue(store.sources().any{it.id==source.id});assertTrue(store.subscriptions().first{it.id==id}.succeeded>0)}
            assertTrue(server.requestCount>0)
        }finally{manager.cancelWorkById(work.id);LibraryStore(context).use{it.deleteSource(source.id);it.deleteSubscription(id)};server.shutdown()}
    }
}
