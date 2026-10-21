package app.shiguang.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI

class FicketRepository(private val repo:ContentRepository,private val context:Context?) {
    private suspend fun request(s:Source,r:RuleRequest)=repo.fetch(r.url,repo.headers(s)+r.headers,method=r.method,body=r.body,contentType=r.contentType)
    private suspend fun load(s:Source,url:String,dynamic:Boolean=false,actions:String="",query:String="",images:String="",selector:String=""):RulePage {
        if(dynamic||actions.isNotBlank()||images.isNotBlank())return RuleWebPage(context ?: error("此源需要 Android 动态网页环境")).load(url,repo.headers(s),actions,query,images,selector)
        return RulePage(url,repo.fetch(url,repo.headers(s)))
    }
    suspend fun search(s:Source,q:String):List<Entry> {
        val r=FicketRules.original(s);val api=FicketRules.format(s)=="comic-api"
        val options=r.optJSONObject("searchRequestOptions")
        val dynamic=r.optString("method")=="nightmare"||r.optBoolean("searchWebview")||options?.optBoolean("webview")==true||options?.optBoolean("mobileWebview")==true||api&&r.optString("ruleSearchUrl").endsWith("@webview")
        val req=runCatching{FicketRules.searchRequest(s,q)}
        // Prefer the public HTTP endpoint when it returns actual results; fall back to page actions.
        if(req.isSuccess) {
            val parsed=runCatching{val target=req.getOrThrow();FicketRules.search(s,request(s,target),target.url)}
            (parsed.exceptionOrNull() as? CancellationException)?.let{throw it}
            if(parsed.isSuccess&&parsed.getOrThrow().isNotEmpty())return parsed.getOrThrow()
            if(!dynamic)return parsed.getOrThrow()
        }
        val page=load(s,req.getOrNull()?.url ?: s.url,true,r.optString("nightmareActions"),q)
        return FicketRules.search(s,page.html,page.url)
    }
    suspend fun detail(s:Source,e:Entry):Detail=withContext(Dispatchers.IO) {
        val r=FicketRules.original(s)
        if(FicketRules.format(s)=="comic-api")return@withContext apiDetail(s,e)
        val c=r.optJSONObject("chapterListRegex") ?: JSONObject()
        var target=e.url
        val custom=c.optString("chapterItemFunc")
        if(custom.contains("content-(.+?)-"))target=target.replace(Regex("content-(.+?)-\\d+\\.html$"),"book-$1.html")
        val dynamic=c.optBoolean("webview")||r.optJSONObject("playlistRequestOptions")?.optBoolean("webview")==true
        val first=load(s,target,dynamic)
        if(c.optString("evalString").contains("/novel/clist/")) {
            val bid=Regex("/read/(\\d+)").find(target)?.groupValues?.get(1) ?: error("目录地址中未找到书籍编号")
            val response=request(s,RuleRequest(ContentRepository.resolve(s.url,"/novel/clist/"),"POST",mapOf("X-Requested-With" to "XMLHttpRequest"),"bid=$bid","application/x-www-form-urlencoded"))
            val a=JSONObject(response).optJSONArray("data") ?: error("目录接口没有返回章节")
            val chapters=(0 until a.length()).map{a.getJSONObject(it)}.filter{it.optString("ctype")=="0"}.map{Chapter(it.getString("title"),target.trimEnd('/')+"/p${it.getString("ordernum")}.html")}
            require(chapters.isNotEmpty()){"目录接口没有公开章节"};return@withContext Detail(e,listOf(Line("章节",chapters)))
        }
        val pages=mutableListOf(first.url to first.html)
        val links=FicketRules.directoryLinks(s,first.html,first.url)
        // A directory link points to a separate TOC; do not combine the "latest chapters" preview.
        if(c.optString("chapterListUrl").isNotBlank()&&links.isNotEmpty())pages.clear()
        for(url in links) {
            val page=load(s,url,dynamic);pages.add(page.url to page.html)
            // A secondary TOC can contain its own pagination selector.
            if(pages.size==1)for(next in FicketRules.directoryLinks(s,page.html,page.url))if(next !in links&&pages.none{it.first==next}) {
                require(pages.size<200){"目录分页超过限制，未截断导入"};val p=load(s,next,dynamic);pages.add(p.url to p.html)
            }
        }
        FicketRules.detail(s,e,pages)
    }
    suspend fun page(s:Source,e:Entry,ch:Chapter):PageContent {
        val r=FicketRules.original(s)
        if(FicketRules.format(s)=="comic-api") {
            if(r.optString("ruleChapterUrl").contains("mgsearcher")) {
                val raw=repo.fetch(ch.url,repo.headers(s));val images=FicketRules.jsonValues(JSONTokener(raw).nextValue(),"$.data.info.images.images[*]").mapNotNull{item->
                    val url=(item as? JSONObject)?.optString("url").orEmpty();url.takeIf{it.isNotBlank()}?.let{ContentRepository.resolve("https://f40-1-4.g-mh.online/",it)}
                }
                require(images.isNotEmpty()){"此章节未提供公开图片"};return PageContent(images=images)
            }
            val dynamic=r.optString("ruleSearchUrl").endsWith("@webview")
            val p=load(s,ch.url,dynamic)
            val images=FicketRules.apiImages(s,p.html,p.url)
            require(images.isNotEmpty()){"接口未提供可公开读取的漫画图片"};return PageContent(images=images)
        }
        if(e.kind==Kind.NOVEL) {
            val c=r.getJSONObject("contentRegex");val page=load(s,ch.url,c.optBoolean("webview"),selector=if(c.optBoolean("webview"))c.getString("content") else "")
            return FicketRules.novelPage(s,page.html,page.url)
        }
        val expression=r.optString("comicImageUrlListsRegex")
        val dynamic=expression.startsWith("webview#")||expression.startsWith("mWebview#")||expression.startsWith("capture$")
        val script=when {
            expression.startsWith("webview#")||expression.startsWith("mWebview#")->expression.substringAfter('#')
            expression.startsWith("capture$")->"Array.from(document.querySelectorAll(${JSONObject.quote(expression.substringAfter('$').substringAfter('$'))})).map(function(e){return e.getAttribute('data-src')||e.getAttribute('data-original')||e.src;}).filter(Boolean)"
            else->""
        }
        val page=load(s,ch.url,dynamic,images=script)
        val images=page.images.ifEmpty{FicketRules.comicImages(s,page.html,page.url)}
        require(images.isNotEmpty()){"未提取到漫画图片：站点可能已改版或需要验证"}
        return PageContent(images=images)
    }
    private suspend fun apiDetail(s:Source,e:Entry):Detail {
        val r=FicketRules.original(s);val raw=if(r.optString("ruleSearchUrl").endsWith("@webview"))load(s,e.url,true).html else repo.fetch(e.url,repo.headers(s));val rule=r.optString("ruleChapterUrl")
        if(rule.contains("mgsearcher")) {
            val id=Jsoup.parse(raw,e.url).selectFirst("#bookmarkData")?.attr("data-mid").orEmpty()
            require(id.matches(Regex("[A-Za-z0-9_-]+"))){"页面未提供漫画编号"}
            val data=repo.fetch("https://api-get-v3.mgsearcher.com/api/manga/get?mid=$id&mode=all",repo.headers(s))
            val chapters=FicketRules.jsonValues(JSONTokener(data).nextValue(),"$.data.chapters[*]").mapNotNull{node->
                val cid=FicketRules.scalar(node,"$.id");if(!cid.matches(Regex("[A-Za-z0-9_-]+")))null else Chapter(FicketRules.scalar(node,"$.attributes.title").ifBlank{"章节"},"https://api-get-v3.mgsearcher.com/api/chapter/getinfo?m=$id&c=$cid")
            };require(chapters.isNotEmpty()){"接口没有公开章节"};return Detail(e,listOf(Line("章节",chapters)))
        }
        if(rule.startsWith("@js:"))error("此旧规则目录使用了站点专用远程脚本，当前接口未完成验证")
        if(rule.isBlank())return FicketRules.apiDetail(s,e,raw)
        val root=JSONTokener(raw).nextValue();val url=FicketRules.render(rule,root,emptyMap())
        return FicketRules.apiDetail(s,e,raw,repo.fetch(url,repo.headers(s)))
    }
}
