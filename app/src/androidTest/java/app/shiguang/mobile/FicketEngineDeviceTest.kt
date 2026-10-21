package app.shiguang.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FicketEngineDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun completeConvertedCollectionImportsAll263WithStableIds(){
        val raw=InstrumentationRegistry.getInstrumentation().context.assets.open("full-rules.json").bufferedReader().use{it.readText()}
        val parsed=SourceCodec.parse(raw);assertEquals(263,parsed.size)
        assertEquals(parsed.map{it.id},SourceCodec.parse(org.json.JSONArray().apply{parsed.forEach{put(it.json())}}.toString()).map{it.id})
        File(context.getExternalFilesDir(null),"full-rules-import-result.json").writeText(JSONObject().put("imported",parsed.size).put("stableIdentity",true).put("engines",org.json.JSONArray(parsed.map{it.engine}.distinct())).toString(2))
    }
    @Test fun postSearchAndMultiPageNovelDirectoryUseTheRealRepository()=runBlocking {
        val requests=java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use{server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{requests.add(r);return MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(when(r.path!!.substringBefore('?')){
                "/search"->"<a class='title' href='/book'>春天</a>"
                "/book"->"<a class='directory' href='/toc'>完整目录</a><div id='toc'><a href='/latest'>只含最新章节的预览</a></div>"
                "/toc"->"<a class='directory' href='/toc2'>下一页</a><div id='toc'><a href='/a'>第一章</a></div>"
                "/toc2"->"<div id='toc'><a href='/b'>第二章</a></div>"
                "/a"->"<div id='content'>春天的正文<br>第二行</div>"
                else->""})}}
            val original=JSONObject("""{"homepageUrl":"${server.url("/")}","method":"post","searchUrlPrefix":"/search","formData":"{\"word\":\"{{searchKey}}\"}","searchResultRegex":{"title":"a.title"},"chapterListRegex":{"chapterListUrl":"a.directory","container":"#toc","item":"a"},"contentRegex":{"content":"#content"}}""")
            val source=SourceCodec.parse(original.toString()).single();val repo=ContentRepository(context)
            val entry=repo.searchOne(source,"春天").single();val detail=repo.detail(source,entry)
            assertEquals(listOf("第一章","第二章"),detail.lines.single().chapters.map{it.title});assertFalse(detail.lines.single().chapters.any{it.url.endsWith("latest")})
            assertTrue(repo.page(source,entry,detail.lines.single().chapters.first()).text.contains("第二行"));assertEquals("POST",requests.first().method);assertTrue(requests.first().body.readUtf8().startsWith("word="))
        }
    }
    @Test fun dynamicComicPageExecutesOnlyInsideTheBrowserAndReturnsImages()=runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use{
            MockWebServer().use{server->
                server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<html><body><script>setTimeout(function(){window.params={images:['${server.url("/1.jpg")}','${server.url("/2.jpg")}']};},80);</script>正文</body></html>"))
                val original=JSONObject("""{"homepageUrl":"${server.url("/")}","searchResultRegex":{"titleRegex":"a"},"comicImageUrlListsRegex":"webview#(() => window.params.images)()"}""")
                val s=SourceCodec.parse(original.toString()).single();val e=Entry("x",s.id,"动态漫画",Kind.COMIC,server.url("/book").toString())
                val page=ContentRepository(context).page(s,e,Chapter("第一话",server.url("/chapter").toString()))
                assertEquals(listOf(server.url("/1.jpg").toString(),server.url("/2.jpg").toString()),page.images)
            }
        }
    }
}
