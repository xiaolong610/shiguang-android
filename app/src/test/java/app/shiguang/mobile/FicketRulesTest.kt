package app.shiguang.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FicketRulesTest {
    private fun source(kind:String,original:JSONObject):Source=SourceCodec.parse(JSONObject().put("name","测试规则").put("kind",kind).put("engine","ficket").put("url","https://example.com/").put("rules",JSONObject().put("original",original)).toString()).single()
    @Test fun nativePostSearchAndCharsetEncodeExactlyOnce(){
        val s=source("novel",JSONObject("""{"homepageUrl":"https://example.com/","method":"post","searchUrlPrefix":"/search","formData":"{\"word\":\"{{searchKey}}\"}","searchResultRegex":{"title":"a"}}"""))
        val request=FicketRules.searchRequest(s,"春天 & 秋天");assertEquals("POST",request.method);assertTrue(request.body!!.contains("%26"));assertFalse(request.body!!.contains("%2526"))
    }
    @Test fun separateTocPagesDoNotLoseOrDuplicateChapters(){
        val s=source("novel",JSONObject("""{"homepageUrl":"https://example.com/","searchResultRegex":{"title":"a"},"chapterListRegex":{"chapterListUrl":"select option","container":"#toc","item":"a"}}"""))
        val e=Entry("x",s.id,"书",Kind.NOVEL,"https://example.com/book")
        val detail=FicketRules.detail(s,e,listOf("https://example.com/toc/1" to "<div id='toc'><a href='/a'>甲</a><a href='/b'>乙</a></div>","https://example.com/toc/2" to "<div id='toc'><a href='/b'>乙</a><a href='/c'>丙</a></div>"))
        assertEquals(listOf("甲","乙","丙"),detail.lines.single().chapters.map{it.title})
        assertEquals(listOf("https://example.com/toc/2"),FicketRules.directoryLinks(s,"<select><option value='/toc/2'>2</option></select>","https://example.com/toc/1"))
    }
    @Test fun reviewedJsonSearchFunctionHasNativeEquivalent(){
        val s=source("comic",JSONObject("""{"homepageUrl":"https://example.com/","searchResultFuncFromJson":"dataFromRes.search_data.map(...)"}"""))
        val entries=FicketRules.search(s,"""{"search_data":[{"id":12,"name":"春天","imgs":"/cover.jpg"}]}""","https://example.com/search")
        assertEquals("春天",entries.single().title);assertEquals("https://example.com/12/",entries.single().url)
    }
    @Test fun cssLegadoFieldsAndLazyImagesAreInterpreted(){
        val root=org.jsoup.Jsoup.parse("<div class='card'><a class='title' href='/book'>春天</a><img data-page-image-url='/page.jpg'></div>")
        assertEquals(listOf("/book"),FicketRules.htmlValues(root,"class.card@class.title@href"));assertEquals("https://example.com/page.jpg",FicketRules.image(root.selectFirst("img"),"https://example.com/"))
    }
}
