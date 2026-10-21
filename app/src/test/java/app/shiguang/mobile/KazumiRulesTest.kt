package app.shiguang.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KazumiRulesTest {
    private fun rule(base:String="https://anime.example/")=JSONObject().put("api","5").put("type","anime").put("name","测试动漫").put("version","1.0").put("baseURL",base).put("searchURL",base+"search?q=@keyword").put("searchList","//li[@class='item']").put("searchName","//a").put("searchResult","//a").put("chapterRoads","//ul[@class='episodes']").put("chapterResult","//li/a")
    @Test fun importPreservesFormatAndStableIdentityAcrossDomainUpdates() {
        val a=SourceCodec.parse(rule().toString()).single();val b=SourceCodec.parse(rule("https://new.example/").toString()).single()
        assertEquals("kazumi",a.engine);assertEquals(a.id,b.id);assertEquals(a,SourceCodec.parse(a.json().toString()).single())
        val advanced=rule().put("searchMode","unknown");assertTrue(runCatching{SourceCodec.parse(advanced.toString())}.isFailure)
    }
    @Test fun xpathIsRelativeToEachResultAndRoad() {
        val s=SourceCodec.parse(rule().toString()).single()
        val entries=KazumiRules.search(s,"<ul><li class=item><a href='/a'>甲</a><img data-src='/a.jpg'></li><li class=item><a href='/b'>乙</a></li></ul>",s.url)
        assertEquals(listOf("甲","乙"),entries.map{it.title});assertEquals("https://anime.example/b",entries.last().url)
        val d=KazumiRules.detail(s,entries.first(),"<ul class=episodes><li><a href='/play/1'>01</a></li></ul><ul class=episodes><li><a href='/line2/1'>01</a></li><li><a href='/line2/2'>02</a></li></ul>")
        assertEquals(listOf(1,2),d.lines.map{it.chapters.size});assertEquals("https://anime.example/line2/2",d.lines.last().chapters.last().url)
    }
    @Test fun postSearchEncodesUnicodeAndUsesRequestHeaders()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val r=rule(server.url("/").toString()).put("usePost",true).put("referer","https://ref.example/")
            val s=SourceCodec.parse(r.toString()).single();server.enqueue(MockResponse().setBody("<li class=item><a href='/detail'>番剧</a></li>"))
            val result=ContentRepository().searchOne(s,"天空 & 海")
            assertEquals(1,result.size);val req=server.takeRequest();assertEquals("POST",req.method);assertEquals("/search",req.path);assertEquals("https://ref.example/",req.getHeader("Referer"));assertTrue(req.body.readUtf8().contains("%26"))
        }finally{server.shutdown()}
    }
    @Test fun jsonPathSupportsKeysIndicesAndWildcardButRejectsFilters() {
        val obj=JSONObject("""{"data":{"items":[{"name":"甲"},{"name":"乙"}]}}""")
        assertEquals(listOf("甲","乙"),JsonRulePath.values(obj,"$.data.items[*].name"));assertEquals("乙",JsonRulePath.scalar(obj,"$['data'].items[1].name"))
        assertTrue(runCatching{JsonRulePath.validate("$.data[?(@.id>1)]")}.isFailure)
    }
    @Test fun sameHostSchemeAndMixedApiXPathDetailLinksFollowRuleBase() {
        assertEquals("https://anime.example/detail/1",KazumiRules.normalize("https://anime.example/","http://anime.example/detail/1"))
        assertEquals("http://other.example/x",KazumiRules.normalize("https://anime.example/","http://other.example/x"))
        val r=rule("https://anime.example/detail/").put("searchMode","api").put("searchApiConfig",JSONObject("""{"request":{"url":"https://api.example/search"},"listPath":"$.list[*]","namePath":"$.name","sourcePath":"$.id"}"""))
        val s=SourceCodec.parse(r.toString()).single()
        assertEquals("https://anime.example/detail/123",KazumiApi.search(s,"""{"list":[{"name":"测试","id":123}]}""").single().url)
    }
    @Test fun apiSearchAndNestedEpisodeTemplatesPreserveSourceId() {
        val r=rule().put("api","9").put("searchMode","api").put("chapterMode","api")
            .put("searchApiConfig",JSONObject("""{"request":{"url":"https://api.example/search","query":{"q":"@keyword"}},"listPath":"$.data[*]","namePath":"$.title","sourcePath":"$.id"}"""))
            .put("chapterApiConfig",JSONObject("""{"request":{"url":"https://api.example/show/@source"},"roadsPath":"$.data","roadNamePath":"","episodesPath":"$.episodes[*]","episodeNamePath":"$.title","episodeUrlPath":"$.number","episodePage":{"url":"https://anime.example/watch/@source/@episodeUrl"}}"""))
        val s=SourceCodec.parse(r.toString()).single();val entry=KazumiApi.search(s,"""{"data":[{"id":12,"title":"天空"}]}""").single()
        assertEquals("12",entry.url);assertEquals("https://api.example/show/12",KazumiApi.request(s,false,source=entry.url).url)
        val d=KazumiApi.detail(s,entry,"""{"data":{"episodes":[{"title":"第1话","number":1}]}}""")
        assertEquals("https://anime.example/watch/12/1",d.lines.single().chapters.single().url)
    }
    @Test fun refreshPreservesDisabledLocallyEditedAndDeletedSources() {
        val old=SourceCodec.parse(rule().toString()).single()
        val remote=SourceCodec.parse(rule().put("version","2.0").toString()).single()
        val link=SubscriptionLink(old.id,old.json().toString())
        val disabled=SubscriptionMerge.plan(listOf(old.copy(enabled=false)),listOf(link),listOf(remote))
        assertFalse(disabled.sources.single().enabled);assertEquals("2.0",JSONObject(disabled.sources.single().rules).getString("version"))
        val edited=SubscriptionMerge.plan(listOf(old.copy(name="自己改名")),listOf(link),listOf(remote))
        assertEquals("自己改名",edited.sources.single().name);assertEquals(1,edited.retainedEdits)
        assertTrue(SubscriptionMerge.plan(emptyList(),listOf(link.copy(removed=true)),listOf(remote)).sources.isEmpty())
    }
    @Test fun upstreamRemovalDisablesAndNewAdFlagDoesNotEnableSource() {
        val old=SourceCodec.parse(rule().toString()).single();val link=SubscriptionLink(old.id,old.json().toString())
        assertFalse(SubscriptionMerge.plan(listOf(old),listOf(link),emptyList()).sources.single().enabled)
        val flagged=old.copy(rules=JSONObject(old.rules).put("knownAds",true).toString())
        assertFalse(SubscriptionMerge.plan(listOf(old),listOf(link),listOf(flagged)).sources.single().enabled)
    }
    @Test fun wholeDocumentRoadSelectorAndLocalIdentityArePreserved() {
        val r=rule().put("chapterRoads","//");val s=SourceCodec.parse(r.toString()).single()
        val e=Entry("1",s.id,"测试",Kind.VIDEO,"https://anime.example/detail")
        assertEquals(1,KazumiRules.detail(s,e,"<li><a href='/play'>一</a></li>").lines.size)
        assertEquals(s.id,SourceCodec.parse(s.copy(name="本地改名").json().toString()).single().id)
    }
}
