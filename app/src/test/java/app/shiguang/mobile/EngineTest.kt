package app.shiguang.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    @Test fun importFicketApiAndPreserveOwnRuleFormat() {
        val source=SourceCodec.parse("""[{"name":"接口","api":"https://example.com/api.php/provide/vod/","type":1}]""").single()
        assertEquals("maccms",source.engine);assertEquals(Kind.VIDEO,source.kind)
        assertEquals(source,SourceCodec.parse(source.json().toString()).single())
        assertEquals(source.id,SourceCodec.parse("https://example.com/api.php/provide/vod/").single().id)
    }
    @Test fun rejectInvalidOrExecutableSchemes() {
        listOf("file:///etc/passwd","javascript:alert(1)","https://user:password@example.com/api").forEach {url->
            assertTrue(runCatching{SourceCodec.parse(url)}.isFailure)
        }
        assertTrue(runCatching{SourceCodec.parse("""{"engine":"html","kind":"novel","url":"https://example.com","rules":{}}""")}.isFailure)
    }
    @Test fun macApiPreservesMultipleLinesAndDollarInMediaQuery() {
        val e=Entry("12","test","一部电影",Kind.VIDEO,"12")
        val json=JSONObject().put("list",org.json.JSONArray().put(JSONObject().put("vod_play_from","HLS\$\$\$MP4")
            .put("vod_play_url","第一集\$https://example.com/1.m3u8?token=a\$b#第二集\$https://example.com/2.m3u8\$\$\$正片\$https://example.com/v.mp4"))).toString()
        val d=MacParser.detail(e,json)
        assertEquals(2,d.lines.size);assertEquals(2,d.lines[0].chapters.size)
        assertEquals("https://example.com/1.m3u8?token=a\$b",d.lines[0].chapters[0].url)
        assertEquals("MP4",d.lines[1].name)
    }
    @Test fun macRequestEncodesChineseAndReplacesExistingQuery() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":1,"list":[{"vod_id":7,"vod_name":"<b>海边</b>","vod_remarks":"完结"}]}"""))
            val source=Source("test","测试",Kind.VIDEO,"maccms",server.url("/api?ac=list&token=keep").toString())
            val entries=ContentRepository().searchOne(source,"海边 & 天空")
            assertEquals("海边",entries.single().title)
            val request=server.takeRequest().requestUrl!!
            assertEquals("海边 & 天空",request.queryParameter("wd"));assertEquals("keep",request.queryParameter("token"))
            assertEquals(listOf("detail"),request.queryParameterValues("ac"))
        } finally {server.shutdown()}
    }
    @Test fun htmlNovelSearchDetailAndBodyWorkAcrossRelativeLinks() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val rule=JSONObject().put("searchUrl","/search?q={query}").put("list",".book").put("title","a")
                .put("chapters",".chapters a").put("content","#content")
            val source=Source("html","小说站",Kind.NOVEL,"html",server.url("/").toString(),rules=rule.toString())
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/book/1'>一本书</a></div>"))
            val repo=ContentRepository();val e=repo.searchOne(source,"一本书").single()
            assertEquals(server.url("/book/1").toString(),e.url)
            server.enqueue(MockResponse().setBody("<div class='chapters'><a href='../chapter/1'>第一章</a></div>"))
            val d=repo.detail(source,e)
            assertEquals(server.url("/chapter/1").toString(),d.lines.single().chapters.single().url)
            server.enqueue(MockResponse().setBody("<div id='content'><script>tracking()</script><p>第一段</p><p>第二段<br>第三行</p></div>"))
            val page=repo.page(source,e,d.lines.single().chapters.single())
            assertTrue(page.text.contains("第一段\n\n第二段\n\n第三行"));assertFalse(page.text.contains("tracking"))
        } finally {server.shutdown()}
    }
    @Test fun brokenSourceDoesNotEraseOtherSearchResults() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            val source=Source("broken","故障源",Kind.NOVEL,"html",server.url("/").toString(),rules="""{"searchUrl":"/","list":".book","title":"a"}""")
            val report=ContentRepository().search(Demo.sources+source,Kind.NOVEL,"")
            assertEquals(1,report.entries.size);assertEquals(1,report.errors.size);assertEquals(2,report.searched)
        } finally {server.shutdown()}
    }
    @Test fun oversizedImportOrResponseIsRejected() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("a".repeat(500)))
            assertTrue(runCatching {ContentRepository().fetch(server.url("/").toString(),maxBytes=100)}.isFailure)
        } finally {server.shutdown()}
    }
}
