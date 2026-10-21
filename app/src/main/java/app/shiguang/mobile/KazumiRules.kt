package app.shiguang.mobile

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.net.URLEncoder
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/** Independent adapter for the public Kazumi rule format. XPath 1.0 only. */
object KazumiRules {
    fun validate(r:JSONObject) {
        require(r.optInt("api",1)<=9){"此Kazumi规则需要更新的引擎（最高支持规则等级9的基础字段）"}
        require(r.optString("type","anime") in setOf("anime","video")){"此版只支持Kazumi影视/动漫规则"}
        for((mode,config,fields) in listOf(
            Triple("searchMode","searchApiConfig",listOf("searchURL","searchList","searchName","searchResult")),
            Triple("chapterMode","chapterApiConfig",listOf("chapterRoads","chapterResult")))) {
            when(r.optString(mode,"xpath")) {
                "xpath"->fields.forEach{require(r.optString(it).isNotBlank()){"Kazumi规则缺少 $it"}}
                "api"->{val c=r.optJSONObject(config) ?: error("缺少 $config");require(c.optJSONObject("request")?.optString("url")?.isNotBlank()==true){"$config 缺少请求地址"};KazumiApi.validate(c,mode=="chapterMode")}
                else->error("不支持的Kazumi规则模式：${r.optString(mode)}")
            }
        }
    }
    fun normalize(base:String,raw:String):String {
        if(raw.isBlank())return ""
        return runCatching {
            val b=java.net.URI(base);val u=b.resolve(raw.trim())
            if(u.host==b.host&&u.port==b.port&&u.scheme in setOf("http","https")&&b.scheme in setOf("http","https")&&u.scheme!=b.scheme)
                java.net.URI(b.scheme,u.userInfo,u.host,u.port,u.path,u.query,u.fragment).toString() else u.toString()
        }.getOrDefault("")
    }
    private class XPathPage(html:String) {
        private val dom=W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(html))
        private val xpath=XPathFactory.newInstance().newXPath()
        val root:Node=dom.documentElement
        fun nodes(context:Node,expression:String):List<Node> {
            if(expression.trim() in setOf("//",".","./"))return listOf(context)
            // Kazumi applies // selectors relative to each result fragment.
            val expr=if(context===root) expression else if(expression.startsWith("//")) ".$expression" else expression
            return try {val ns=xpath.evaluate(expr,context,XPathConstants.NODESET) as NodeList;(0 until ns.length).map{ns.item(it)}}
                catch(e:Exception){error("XPath选择器暂不兼容：${expression.take(100)}")}
        }
        fun first(context:Node,expression:String)=nodes(context,expression).firstOrNull()
    }
    private fun attr(n:Node?,name:String)=n?.attributes?.getNamedItem(name)?.nodeValue.orEmpty()
    private fun text(n:Node?)=n?.textContent?.trim().orEmpty()
    fun search(s:Source,html:String,pageUrl:String):List<Entry> {
        val r=JSONObject(s.rules);checkChallenge(r,html)
        val p=XPathPage(html)
        return p.nodes(p.root,r.getString("searchList")).take(100).mapNotNull {node->
            val name=text(p.first(node,r.getString("searchName")))
            val link=p.first(node,r.getString("searchResult"))
            val raw=if(link?.nodeType==Node.ATTRIBUTE_NODE)link.nodeValue else attr(link,"href")
            val url=normalize(s.url,raw.orEmpty())
            if(name.isBlank()||raw.isNullOrBlank()||!ContentRepository.isHttp(url))null else {
                val image=p.first(node,".//img")
                val cover=listOf("data-src","data-original","src").map{attr(image,it)}.firstOrNull{it.isNotBlank()}.orEmpty()
                Entry(SourceCodec.stableId(url),s.id,name,Kind.VIDEO,url,cover.takeIf{it.isNotBlank()}?.let{ContentRepository.resolve(pageUrl,it)}.orEmpty(),"动漫 · ${s.name}")
            }
        }.distinctBy{it.key}
    }
    fun detail(s:Source,e:Entry,html:String):Detail {
        val r=JSONObject(s.rules);checkChallenge(r,html);val p=XPathPage(html)
        val lines=p.nodes(p.root,r.getString("chapterRoads")).take(30).mapIndexedNotNull{i,node->
            val chapters=p.nodes(node,r.getString("chapterResult")).take(3000).mapIndexedNotNull{j,ch->
                val raw=if(ch.nodeType==Node.ATTRIBUTE_NODE) ch.nodeValue else attr(ch,"href")
                val url=normalize(s.url,raw)
                if(raw.isBlank()||!ContentRepository.isHttp(url))null else Chapter(text(ch).ifBlank{"第${j+1}集"},url)
            }.distinctBy{it.url}
            if(chapters.isEmpty())null else Line("播放线路 ${i+1}",chapters)
        }
        require(lines.isNotEmpty()){"未提取到剧集，站点可能已改版或需要网页验证"}
        return Detail(e,lines)
    }
    fun checkChallenge(r:JSONObject,html:String) {
        val c=r.optJSONObject("antiCrawlerConfig") ?: return
        if(!c.optBoolean("enabled"))return
        val value=c.optString("captchaDetectValue")
        val detected=when(c.optString("captchaDetectType","xpath")) {
            "text"->value.isNotBlank()&&html.contains(value)
            "regex"->value.isNotBlank()&&Regex(value,RegexOption.IGNORE_CASE).containsMatchIn(html)
            else->value.isNotBlank()&&XPathPage(html).let{it.nodes(it.root,value).isNotEmpty()}
        }
        require(!detected){"站点需要网页验证，当前请换源或在浏览器观看"}
    }
    fun searchUrl(s:Source,q:String)=ContentRepository.resolve(s.url,JSONObject(s.rules).getString("searchURL").replace("@keyword",URLEncoder.encode(q,"UTF-8")))
}
