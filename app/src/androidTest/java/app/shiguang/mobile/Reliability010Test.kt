package app.shiguang.mobile

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.SuccessResult
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Reliability010Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation();private val context get()=ins.targetContext
    private fun model():AppModel {lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};return m}
    @Test fun constructingViewModelDoesNotWaitForLockedDatabaseOnMainThread(){
        model();val locked=CountDownLatch(1);val release=CountDownLatch(1);val viewModels=ViewModelStore()
        val worker=Thread{LibraryStore(context).use{db->db.writableDatabase.beginTransaction();try{locked.countDown();release.await(5,TimeUnit.SECONDS)}finally{db.writableDatabase.endTransaction()}}}
        worker.start();assertTrue(locked.await(3,TimeUnit.SECONDS));lateinit var m:AppModel
        try{val start=System.nanoTime();ins.runOnMainSync{m=ViewModelProvider(viewModels,ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[AppModel::class.java]}
            assertTrue("界面线程等待了数据库锁",(System.nanoTime()-start)/1_000_000<2000)
        }finally{release.countDown();worker.join(6000)}
        compose.waitUntil(15000){m.libraryIdle};ins.runOnMainSync{viewModels.clear()}
    }
    @Test fun scrollingHoldsVisibleResultsUntilUserRevealsNewMatches(){
        val m=model();val server=okhttp3.mockwebserver.MockWebServer();server.start()
        val sources=listOf("fast","slow").map{path->Source(SourceCodec.stableId("stable010:$path"),path,Kind.VIDEO,"maccms",server.url("/$path").toString())}
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse{
            val slow=request.requestUrl!!.encodedPath=="/slow";val rows=org.json.JSONArray()
            if(slow)rows.put(org.json.JSONObject().put("vod_id","99").put("vod_name","稳定"))else repeat(35){rows.put(org.json.JSONObject().put("vod_id",it.toString()).put("vod_name","稳定结果%02d".format(it)))}
            return okhttp3.mockwebserver.MockResponse().setBody(org.json.JSONObject().put("list",rows).toString()).apply{if(slow)setBodyDelay(3,TimeUnit.SECONDS)}
        }}
        try{
            compose.activityRule.scenario.onActivity{m.store.putSources(sources);m.refreshSources();m.search("稳定")}
            compose.waitUntil(10000){m.entries.size==35}
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("稳定结果25"))
            val first=m.entries.first().title
            compose.waitUntil(10000){!m.loading&&m.hasPendingResults}
            assertEquals(first,m.entries.first().title);assertEquals(1,m.pendingResultCount)
            compose.onNodeWithText("新结果 1").performClick()
            compose.waitUntil(5000){m.entries.firstOrNull()?.title=="稳定"};assertEquals(36,m.entries.size)
        }finally{compose.activityRule.scenario.onActivity{m.stopSearch();sources.forEach{m.store.deleteSource(it.id)};m.refreshSources()};server.shutdown()}
    }
    @Test fun externalWritesUpdateShelfWithoutPollingAndRollbackPublishesNoPartialData(){
        val m=model();val entry=Entry("event010","fixture010","变化通知验证",Kind.NOVEL,"https://example.com/event010")
        try{
            compose.onNodeWithContentDescription("书架",useUnmergedTree=true).performClick();compose.onNodeWithContentDescription("切换小说分类").performClick()
            LibraryStore(context).use{it.favorite(entry,true)}
            compose.waitUntil(3000){compose.onAllNodesWithText(entry.title).fetchSemanticsNodes().isNotEmpty()}
            assertTrue(m.favorites.any{it.key==entry.key})
            val before=LibraryEvents.revision.value
            LibraryStore(context).use{db->runCatching{db.transaction{db.favorite(entry.copy(id="rollback"),true);error("rollback")}};assertFalse(db.isFavorite("fixture010::rollback"))}
            assertEquals(before,LibraryEvents.revision.value)
            LibraryStore(context).use{it.favorite(entry,false)}
            compose.waitUntil(3000){compose.onAllNodesWithText(entry.title).fetchSemanticsNodes().isEmpty()}
        }finally{LibraryStore(context).use{it.favorite(entry,false)}}
    }
    @Test fun sharedImageLoaderBoundsTallBitmapAndCacheCleanupPreservesUserFiles()=runBlocking {
        model();context.stopService(Intent(context,PlaybackService::class.java));delay(200)
        val image=File(context.cacheDir,"tall010.png");val marker=File(context.filesDir,"novels/.keep010.txt").apply{parentFile!!.mkdirs();writeText("本地书籍不能被缓存清理删除")}
        val font=File(context.filesDir,"reader-fonts/.keep010.txt").apply{parentFile!!.mkdirs();writeText("字体也需要保留")}
        try{
            Bitmap.createBitmap(300,18000,Bitmap.Config.RGB_565).let{bitmap->image.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            val loader=AppImages.get(context);assertSame(loader,ReadingPreload.images(context))
            val request=ReadingPreload.image(context,ContentRepository(context),null,Uri.fromFile(image).toString(),1080).newBuilder().allowHardware(false).build()
            val result=loader.execute(request);assertTrue(result is SuccessResult)
            val bitmap=((result as SuccessResult).drawable as BitmapDrawable).bitmap;assertTrue(bitmap.height<=8192);assertTrue(bitmap.width<=1440)
            AppStorage.clear(context,"images");AppStorage.clear(context,"memory")
            assertEquals("本地书籍不能被缓存清理删除",marker.readText());assertTrue(font.exists());assertEquals(0L,AppStorage.sizes(context).memory)
            compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{StorageSheet{}}}}
            compose.onNodeWithText("缓存管理").performClick();compose.onNodeWithText("封面与漫画图片").assertIsDisplayed()
            ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"reliability010-cache.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        }finally{image.delete();marker.delete();font.delete()}
    }
}
