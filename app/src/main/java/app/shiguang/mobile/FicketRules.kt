package app.shiguang.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Ficket's shipped declarative fields are interpreted here, never eval'ed as Android code. */
object FicketRules {
    fun original(s:Source)=JSONObject(s.rules).optJSONObject("original") ?: JSONObject(s.rules)
    fun format(s:Source)=JSONObject(s.rules).optString("format").ifBlank {
        val r=original(s);when { r.has("bookSourceName")->"comic-api";s.kind==Kind.VIDEO->"video";s.kind==Kind.COMIC->"comic";else->"novel" }
    }
    fun validate(r:JSONObject) {
        val o=r.optJSONObject("original") ?: r
        require(o.optString("homepageUrl").isNotBlank()||o.optString("bookSourceUrl").isNotBlank()){"Ficket规则缺少站点地址"}
        require(o.has("searchResultRegex")||o.has("titleRegex")||o.has("ruleSearchList")||o.optString("searchResultFuncFromJson").contains("search_data")){"Ficket规则缺少搜索字段"}
    }
    private fun abs(base:String,raw:String)=raw.trim().takeIf{it.isNotBlank()&&!it.startsWith('#')}?.let{ContentRepository.resolve(base,it)}?.takeIf{ContentRepository.isHttp(it)}.orEmpty()
    fun image(el:Element?,base:String):String {
        if(el==null)return ""
        val raw=listOf("data-src","data-original","data-page-image-url","src","data-lazy-src").map{el.attr(it)}.firstOrNull{it.isNotBlank()&&!it.startsWith("data:")}
            ?: Regex("url\\(['\"]?([^)'\"]+)").find(el.attr("style"))?.groupValues?.get(1).orEmpty()
        return abs(base,raw)
    }
    fun searchRequest(s:Source,q:String):RuleRequest {
        val r=original(s)
        if(format(s)=="comic-api")return RuleRequest(render(r.getString("ruleSearchUrl").removeSuffix("@webview"),null,emptyMap(),q))
        val charset=r.optString("searchUrlCharacterSet","UTF-8").ifBlank{"UTF-8"}
        val encoded=URLEncoder.encode(q,charset)
        val url=abs(s.url,r.optString("searchUrlPrefix").replace("{{searchKey}}",encoded))
        require(url.isNotBlank()){"此源通过网页搜索表单查询"}
        if(r.optString("method").equals("post",true)) {
            val form=JSONObject(r.optString("formData","{}").ifBlank{"{}"})
            val body=form.keys().asSequence().joinToString("&"){URLEncoder.encode(it,charset)+"="+URLEncoder.encode(form.get(it).toString().replace("{{searchKey}}",q),charset)}
            return RuleRequest(url,"POST",body=body,contentType="application/x-www-form-urlencoded; charset=$charset")
        }
        return RuleRequest(url)
    }
    fun search(s:Source,raw:String,pageUrl:String):List<Entry> {
        val r=original(s)
        if(format(s)=="comic-api")return apiSearch(s,raw,pageUrl)
        if(r.optString("searchResultFuncFromJson").contains("search_data")){
            val data=JSONObject(raw).optJSONArray("search_data")?:error("搜索接口没有返回search_data")
            return (0 until data.length()).mapNotNull{i->val item=data.getJSONObject(i);val id=item.optString("id");val name=item.optString("name");val url=abs(s.url,"$id/")
                if(id.isBlank()||name.isBlank()||url.isBlank())null else Entry(SourceCodec.stableId(url),s.id,name,s.kind,url,abs(s.url,item.optString("imgs")))}
        }
        val doc=Jsoup.parse(raw,pageUrl);val sr=r.optJSONObject("searchResultRegex") ?: JSONObject()
        val title=when(s.kind){Kind.VIDEO->r.optString("titleRegex");Kind.COMIC->sr.optString("titleRegex");Kind.NOVEL->sr.optString("title")}
        val link=when(s.kind){Kind.VIDEO->r.optString("videoDetailUrlRegex").ifBlank{title};Kind.COMIC->sr.optString("detailRegex").ifBlank{title};Kind.NOVEL->title}
        val cover=when(s.kind){Kind.VIDEO->r.optString("imgUrlRegex");Kind.COMIC->sr.optString("imageRegex");Kind.NOVEL->sr.optString("novelImage")}
        val titles=doc.select(title);val links=doc.select(link);val covers=if(cover.isBlank())emptyList() else doc.select(cover)
        val author=sr.optString("author").takeIf{it.isNotBlank()}?.let{doc.select(it)}
        return titles.take(100).mapIndexedNotNull{i,t->
            val href=abs(pageUrl,(links.getOrNull(i) ?: t.closest("a"))?.attr("href").orEmpty())
            val name=t.text().ifBlank{t.attr("title")}.trim()
            if(href.isBlank()||name.isBlank())null else Entry(SourceCodec.stableId(href),s.id,name,s.kind,href,image(covers.getOrNull(i),pageUrl),author?.getOrNull(i)?.text().orEmpty())
        }.distinctBy{it.url}
    }
    fun chapterSelector(s:Source):String {
        val r=original(s);val c=r.optJSONObject("chapterListRegex") ?: JSONObject()
        val script=c.optString("evalString").ifBlank{c.optString("chapterItemFunc")}
        // Reviewed custom functions are translated to native selectors and bounded pagination.
        if(script.contains("#chapter-items"))return "#chapter-items div > a, #chapters_other_list div > a"
        if(script.contains("div.book_list.book_list2"))return "div.book_list.book_list2 > ul > li > a"
        val container=if(s.kind==Kind.VIDEO)r.optString("playlistContainerRegex") else if(s.kind==Kind.COMIC)c.optString("chapterContainerRegex") else c.optString("container")
        val item=if(s.kind==Kind.VIDEO)r.optString("playlistItemRegex") else if(s.kind==Kind.COMIC)c.optString("chapterItemRegex") else c.optString("item")
        return "$container $item".trim()
    }
    fun directoryLinks(s:Source,html:String,url:String):List<String> {
        val r=original(s);val c=r.optJSONObject("chapterListRegex") ?: return emptyList();val doc=Jsoup.parse(html,url)
        val selector=c.optString("chapterListUrl").trim()
        val script=c.optString("evalString")
        val selectors=mutableListOf<String>()
        if(selector.isNotBlank())selectors.add(selector)
        if(script.contains("#indexselect"))selectors.add("#indexselect option")
        if(script.contains("span.middle"))selectors.add("span.middle > select option")
        if(script.contains("getAllPageLinks")&&script.contains("select"))selectors.add("select option")
        val out=selectors.flatMap{doc.select(it)}.map{abs(url,it.attr(if(it.tagName()=="option")"value" else "href"))}.filter{it.isNotBlank()}.toMutableList()
        if(script.contains("totalPages")&&script.contains("index_")) {
            val n=Regex("/(\\d+)").find(doc.select("div.pages").text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
            require(n<=200){"目录分页过多（最多200页），为避免漏章已停止"}
            for(i in 2..n)out.add(abs(url.trimEnd('/')+"/","index_$i.html"))
        }
        require(out.size<=200){"目录分页超过200页，为避免漏章已停止"}
        return out.filter{it!=url}.distinct()
    }
    fun detail(s:Source,e:Entry,pages:List<Pair<String,String>>):Detail {
        val r=original(s);val c=r.optJSONObject("chapterListRegex") ?: JSONObject()
        val selector=chapterSelector(s)
        require(selector.isNotBlank()){"此规则缺少目录选择器"}
        val chapters=pages.flatMap{(url,raw)->Jsoup.parse(raw,url).select(selector).mapNotNull {el->
            val href=abs(url,el.attr("href"));if(href.isBlank())null else Chapter(el.text().ifBlank{el.attr("title")}.ifBlank{"章节"},href)
        }}.distinctBy{it.url}
        require(chapters.isNotEmpty()){"未提取到目录：站点改版、关闭或需要网页验证"}
        require(chapters.size<=30000){"目录超过30000章，未截断导入"}
        if(s.kind==Kind.VIDEO) {
            val doc=Jsoup.parse(pages.first().second,pages.first().first)
            val groups=doc.select(r.optString("playlistContainerRegex"))
            val labels=r.optString("playlistLabelRegex").takeIf{it.isNotBlank()}?.let{doc.select(it)}
            val lines=groups.mapIndexedNotNull{i,g->
                val ch=g.select(r.optString("playlistItemRegex")).mapNotNull{el->abs(pages.first().first,el.attr("href")).takeIf{it.isNotBlank()}?.let{Chapter(el.text().ifBlank{"播放"},it)}}.distinctBy{it.url}
                ch.takeIf{it.isNotEmpty()}?.let{Line(labels?.getOrNull(i)?.text().orEmpty().ifBlank{"线路 ${i+1}"},it)}
            }
            if(lines.isNotEmpty())return Detail(e,lines)
        }
        val reverse=c.optBoolean("listReverse")||c.optBoolean("chapterListReverse")
        return Detail(e,listOf(Line("章节",if(reverse)chapters.reversed() else chapters)))
    }
    fun novelPage(s:Source,html:String,url:String):PageContent {
        val r=original(s).getJSONObject("contentRegex");val doc=Jsoup.parse(html,url);val body=doc.select(r.getString("content"))
        require(body.isNotEmpty()){"未提取到章节正文：检查规则或网站状态"}
        body.select("script,style,iframe,button,nav,advertisement").remove()
        body.select("br").forEach{it.before("\\n")};body.select("p,div").forEach{it.prepend("\\n")}
        val text=body.text().replace("\\n","\n").split('\n').map{it.trim()}.filter{it.isNotBlank()}.joinToString("\n\n")
        require(text.isNotBlank()){"章节正文为空"};return PageContent(text=text)
    }
    fun comicImages(s:Source,html:String,url:String):List<String> {
        val r=original(s);val expression=r.optString("comicImageUrlListsRegex")
        if(expression.startsWith("webview#")||expression.startsWith("mWebview#"))return emptyList()
        val doc=Jsoup.parse(html,url)
        if(expression.contains("getImgs(url)")&&expression.contains("totalPages")) {
            val first=image(doc.selectFirst("body > table > tbody > tr:nth-child(3) > td > table > tbody > tr > td > a > img"),url)
            val n=doc.select("body > table > tbody > tr:nth-child(4) > td > a").mapNotNull{it.text().toIntOrNull()}.maxOrNull() ?: 0
            require(n in 1..1000&&!first.isBlank()){"漫画页数或首图不可用"}
            return (1..n).map{first.replace(Regex("/\\d+\\.jpg$"),"/${it.toString().padStart(3,'0')}.jpg")}
        }
        val selector=if(expression.startsWith("capture$"))expression.substringAfter('$').substringAfter('$') else expression
        if(selector.isBlank()||selector.contains("return "))return emptyList()
        return doc.select(selector).map{image(it,url)}.filter{it.isNotBlank()}.distinct()
    }

    /** Existing API rules use JSONPath, CSS/Legado fields and a small reviewed script subset. */
    fun jsonValues(root:Any?,rule:String):List<Any> {
        val clean=rule.removePrefix("@json:").substringBefore("@put:").substringBefore("@Header:").substringBefore("@js:").trim()
        for(part in clean.split("||")) {
            if(part.isBlank())continue
            val path=if(part.startsWith('$'))part else "$."+part
            val normalized=path.replace(Regex("\\.\\*(?!\\.)"),"[*]")
            val values=if(normalized.contains("..")) {
                val head=normalized.substringBefore("..");val tail=normalized.substringAfter("..")
                fun descendants(node:Any?):List<Any> = when(node){
                    is JSONObject->node.keys().asSequence().flatMap{k->(if(k==tail)listOfNotNull(node.opt(k)) else descendants(node.opt(k))).asSequence()}.toList()
                    is JSONArray->(0 until node.length()).flatMap{descendants(node.opt(it))}
                    else->emptyList()
                }
                JsonRulePath.values(root,head).flatMap{descendants(it)}
            } else JsonRulePath.values(root,normalized)
            val flat=values.flatMap{if(it is JSONArray)(0 until it.length()).mapNotNull{j->it.opt(j)} else listOf(it)}
            if(flat.isNotEmpty())return flat
        };return emptyList()
    }
    fun scalar(root:Any?,rule:String)=jsonValues(root,rule).firstOrNull()?.takeUnless{it is JSONObject||it is JSONArray}?.toString().orEmpty()
    fun css(rule:String):String {
        val p=rule.removePrefix("@css:").split('@').filter{it.isNotBlank()}
        return p.filter{it !in setOf("href","src","text","title","value","data-original","data-page-image-url")}.joinToString(" ") {v->
            val cleaned=v.replace("[0:]","").replace(Regex("\\.(\\d+)$"),":eq($1)")
            when{cleaned.startsWith("class.")->"."+cleaned.drop(6);cleaned.startsWith("tag.")->cleaned.drop(4);cleaned.startsWith("id.")->"#"+cleaned.drop(3);else->cleaned}
        }.trim()
    }
    fun htmlValues(root:Element,rule:String):List<String> {
        for(p in rule.split("||")) {
            val attr=p.substringAfterLast('@',"").takeIf{it in setOf("href","src","title","value","data-original","data-page-image-url")}
                ?: p.takeIf{it in setOf("href","src","value")}
            val select=css(p).takeUnless{it in setOf("href","src","value","text")}.orEmpty()
            val nodes=if(select.isBlank())listOf(root) else root.select(select)
            val out=nodes.map{if(attr==null)it.text() else it.attr(attr)}.filter{it.isNotBlank()}
            if(out.isNotEmpty())return out
        };return emptyList()
    }
    fun render(template:String,node:Any?,vars:Map<String,String>,query:String=""):String {
        var t=template.substringBefore("@Header:").trim().replace("searchKey",URLEncoder.encode(query,"UTF-8")).replace("searchPage","1")
        t=Regex("@get:\\{([^}]+)}").replace(t){vars[it.groupValues[1]].orEmpty()}
        t=Regex("\\{([^}]+)}").replace(t){m->val k=m.groupValues[1];(if(k.startsWith('$'))scalar(node,k) else vars[k] ?: scalar(node,"$.$k"))}
        return t
    }
    private fun apiSearch(s:Source,raw:String,url:String):List<Entry> {
        val r=original(s);val json=r.optString("ruleSearchList").startsWith('$')
        val root=if(json)JSONTokener(raw).nextValue() else Jsoup.parse(raw,url)
        val nodes=if(json)jsonValues(root,r.getString("ruleSearchList")) else (root as Element).select(css(r.getString("ruleSearchList")))
        return nodes.take(100).mapNotNull{node->
            fun field(key:String)=if(json)scalar(node,r.optString(key)) else htmlValues(node as Element,r.optString(key)).firstOrNull().orEmpty()
            val name=field("ruleSearchName");val target=if(json)render(r.getString("ruleSearchNoteUrl"),node,emptyMap()) else field("ruleSearchNoteUrl")
            val href=abs(url,target);val imageRule=r.optString("ruleSearchCoverUrl")
            val cover=if(json&&imageRule.startsWith("http"))render(imageRule,node,emptyMap()) else field("ruleSearchCoverUrl")
            if(name.isBlank()||href.isBlank())null else Entry(SourceCodec.stableId(href),s.id,Jsoup.parse(name).text(),Kind.COMIC,href,abs(url,cover),field("ruleSearchAuthor"))
        }.distinctBy{it.url}
    }
    fun apiDetail(s:Source,e:Entry,raw:String,chapterRaw:String=raw):Detail {
        val r=original(s);val isJson=r.getString("ruleSearchList").startsWith('$')||r.optString("ruleChapterUrl").startsWith("@js:")
        val root=if(isJson)JSONTokener(raw).nextValue() else Jsoup.parse(raw,e.url)
        val chaptersRoot=if(isJson)JSONTokener(chapterRaw).nextValue() else root
        val vars=mutableMapOf<String,String>()
        if(isJson){for(path in listOf("$.data.comic_id","$.comic.id","$.comic_id","$.data.id")){val id=scalar(root,path);if(id.isNotBlank()){vars["comic_id"]=id;break}}}
        val rule=r.getString("ruleChapterList")
        val nodes=if(rule.startsWith("@js:")&&rule.contains("chapter_json")) {
            val text=scalar(root,"$.chapter_json");val a=JSONArray(text);(0 until a.length()).flatMap{jsonValues(a.get(it),"$.data[*]")}
        } else if(isJson)jsonValues(chaptersRoot,rule) else (root as Element).select(css(rule))
        val chapters=nodes.mapNotNull{node->
            val name=if(isJson)scalar(node,r.getString("ruleChapterName")) else htmlValues(node as Element,r.getString("ruleChapterName")).firstOrNull().orEmpty()
            val target=if(isJson)render(r.getString("ruleContentUrl"),node,vars) else htmlValues(node as Element,r.getString("ruleContentUrl")).firstOrNull().orEmpty()
            abs(e.url,target).takeIf{it.isNotBlank()}?.let{Chapter(name.ifBlank{"章节"},it)}
        }.distinctBy{it.url}
        require(chapters.isNotEmpty()){"接口未提供可读取的章节（可能改版、登录或付费限制）"}
        return Detail(e,listOf(Line("章节",chapters)))
    }
    fun apiImages(s:Source,raw:String,url:String):List<String> {
        val r=original(s);val rule=r.getString("ruleBookContent")
        if(rule.startsWith("@regex:")||rule.contains("chapterImages")) {
            val list=Regex("chapterImages\\s*=\\s*\\[(.*?)]",RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1).orEmpty()
            return Regex("['\"]([^'\"]+)['\"]").findAll(list).map{m->val v=m.groupValues[1].replace("\\/","/").replace("\\","");abs(if(rule.contains("pic92"))"https://pic92.tingliu.cc/" else url,v)}.filter{it.isNotBlank()}.toList()
        }
        if(rule.startsWith('$')||rule.startsWith("@json:"))return jsonValues(JSONTokener(raw).nextValue(),rule).map{abs(url,it.toString())}.filter{it.isNotBlank()}
        return htmlValues(Jsoup.parse(raw,url),rule).map{abs(url,it)}.filter{it.isNotBlank()}
    }
}
