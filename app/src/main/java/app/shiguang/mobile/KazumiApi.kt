package app.shiguang.mobile

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class RuleRequest(val url:String,val method:String="GET",val headers:Map<String,String> = emptyMap(),val body:String?=null,val contentType:String="application/json")

/** Deliberately bounded JSONPath subset: keys, indices and array wildcard. */
object JsonRulePath {
    private fun tokens(path:String):List<String> {
        require(path.startsWith('$')){"JSONPath需要以 $ 开头"}
        val out=mutableListOf<String>();var i=1
        while(i<path.length) {
            when(path[i]) {
                '.'->{i++;val start=i;while(i<path.length&&(path[i].isLetterOrDigit()||path[i]=='_'||path[i]=='-'))i++;require(i>start){"不支持此JSONPath：$path"};out.add(path.substring(start,i))}
                '['->{val end=path.indexOf(']',i);require(end>i){"JSONPath括号不完整"};val t=path.substring(i+1,end);require(t=="*"||t.toIntOrNull()!=null||(t.startsWith("'")&&t.endsWith("'"))||(t.startsWith('"')&&t.endsWith('"'))){"暂不支持JSONPath过滤器或递归：$path"};out.add(if(t.startsWith("'")||t.startsWith('"'))t.substring(1,t.length-1) else "[$t]");i=end+1}
                else->error("不支持此JSONPath：$path")
            }
        };return out
    }
    fun validate(path:String){if(path.isNotBlank())tokens(path)}
    fun values(root:Any?,path:String):List<Any> {
        if(path.isBlank())return emptyList()
        var values=listOfNotNull(root?.takeUnless{it===JSONObject.NULL})
        for(t in tokens(path))values=values.flatMap{node->when {
            t=="[*]"&&node is JSONArray->(0 until node.length()).mapNotNull{node.opt(it)?.takeUnless{v->v===JSONObject.NULL}}
            t=="[*]"&&node is JSONObject->node.keys().asSequence().mapNotNull{node.opt(it)?.takeUnless{v->v===JSONObject.NULL}}.toList()
            t.startsWith('[')&&node is JSONArray->listOfNotNull(node.opt(t.drop(1).dropLast(1).toInt())?.takeUnless{it===JSONObject.NULL})
            node is JSONObject->listOfNotNull(node.opt(t)?.takeUnless{it===JSONObject.NULL})
            else->emptyList()
        }}
        return values
    }
    fun scalar(root:Any?,path:String)=values(root,path).firstOrNull()?.takeUnless{it is JSONArray||it is JSONObject}?.toString().orEmpty()
}

object KazumiApi {
    fun validate(c:JSONObject,chapter:Boolean) {
        for(key in c.keys())if(key.endsWith("Path"))JsonRulePath.validate(c.optString(key))
        c.optJSONObject("variables")?.let {v->for(k in v.keys())JsonRulePath.validate(v.getString(k))}
        val req=c.getJSONObject("request")
        require(req.optString("method","GET").uppercase() in setOf("GET","POST")){"API规则只支持GET/POST"}
        require(req.optString("bodyType","none") in setOf("none","json","form")){"不支持API请求体类型"}
        if(chapter)require(c.optString("format","nested") in setOf("nested","delimited")){"不支持此剧集API格式"}
    }
    private val token=Regex("@[A-Za-z][A-Za-z0-9_]*")
    fun render(text:String,vars:Map<String,String>,encoded:Boolean=false):String=token.replace(text){m->
        val value=vars[m.value.drop(1)] ?: error("规则变量暂不支持：${m.value}")
        if(encoded)URLEncoder.encode(value,"UTF-8").replace("+","%20") else value
    }
    private fun url(text:String,vars:Map<String,String>):String {
        if(text.startsWith('@')&&token.matches(text))return SourceCodec.httpUrl(render(text,vars))
        return SourceCodec.httpUrl(render(text,vars,true))
    }
    private fun renderObject(value:Any?,vars:Map<String,String>):Any?=when(value){
        is JSONObject->JSONObject().apply{for(k in value.keys())put(k,renderObject(value.opt(k),vars))}
        is JSONArray->JSONArray().apply{for(i in 0 until value.length())put(renderObject(value.opt(i),vars))}
        is String->render(value,vars)
        else->value
    }
    fun request(s:Source,search:Boolean,keyword:String="",source:String=""):RuleRequest {
        val r=JSONObject(s.rules);val c=r.getJSONObject(if(search)"searchApiConfig" else "chapterApiConfig");val req=c.getJSONObject("request")
        val vars=mapOf("keyword" to keyword,"source" to source)
        val builder=url(req.getString("url"),vars).toHttpUrl().newBuilder()
        req.optJSONObject("query")?.let{q->for(k in q.keys())builder.addQueryParameter(k,render(q.get(k).toString(),vars))}
        val headers=mutableMapOf<String,String>();req.optJSONObject("headers")?.let{h->for(k in h.keys())headers[k]=render(h.get(k).toString(),vars)}
        val type=req.optString("bodyType","none")
        val body=when(type){
            "json"->renderObject(req.opt("body"),vars)?.toString() ?: "{}"
            "form"->{val obj=req.optJSONObject("body") ?: JSONObject();obj.keys().asSequence().joinToString("&"){URLEncoder.encode(it,"UTF-8")+"="+URLEncoder.encode(render(obj.get(it).toString(),vars),"UTF-8")}}
            else->null
        }
        return RuleRequest(builder.build().toString(),req.optString("method","GET").uppercase(),headers,body,if(type=="form")"application/x-www-form-urlencoded" else "application/json")
    }
    fun search(s:Source,raw:String):List<Entry> {
        val root=org.json.JSONTokener(raw).nextValue();val c=JSONObject(s.rules).getJSONObject("searchApiConfig")
        return JsonRulePath.values(root,c.optString("listPath","$.data[*]")).take(100).mapNotNull {n->
            val title=JsonRulePath.scalar(n,c.optString("namePath","$.name"));val source=JsonRulePath.scalar(n,c.optString("sourcePath","$.url"))
            if(title.isBlank()||source.isBlank())null else {
                val template=c.optString("sourceTemplate");val target=if(template.isNotBlank())url(template,mapOf("source" to source)) else if(JSONObject(s.rules).optString("chapterMode","xpath")=="xpath")KazumiRules.normalize(s.url,source) else source
                Entry(SourceCodec.stableId(target),s.id,JsoupText.clean(title),Kind.VIDEO,target,subtitle="动漫 · ${s.name}")
            }
        }.distinctBy{it.key}
    }
    fun detail(s:Source,e:Entry,raw:String):Detail {
        val root=org.json.JSONTokener(raw).nextValue();val c=JSONObject(s.rules).getJSONObject("chapterApiConfig");val vars=mutableMapOf("source" to e.url)
        c.optJSONObject("variables")?.let{v->for(k in v.keys())vars[k]=JsonRulePath.scalar(root,v.getString(k))}
        fun chapter(name:String,rawUrl:String,road:Int,index:Int):Chapter? {
            val page=c.optJSONObject("episodePage")
            val target=if(page!=null&&page.optString("url").isNotBlank()) {
                val v=vars+mapOf("episodeUrl" to rawUrl,"episodeName" to name,"roadIndex" to road.toString(),"episodeIndex" to index.toString())
                val b=url(page.getString("url"),v).toHttpUrl().newBuilder();page.optJSONObject("query")?.let{q->for(k in q.keys())b.addQueryParameter(k,render(q.get(k).toString(),v))};b.build().toString()
            } else ContentRepository.resolve(s.url,rawUrl)
            return if(!ContentRepository.isHttp(target)||rawUrl.isBlank()&&page==null)null else Chapter(name.ifBlank{"第${index+1}集"},target)
        }
        val lines=if(c.optString("format","nested")=="delimited") {
            val separator=c.optString("roadSeparator","$$$");require(separator.isNotEmpty())
            val names=JsonRulePath.scalar(root,c.optString("roadNamesPath")).split(separator)
            JsonRulePath.scalar(root,c.optString("roadEpisodesPath")).split(separator).take(30).mapIndexedNotNull {i,road->
                val es=c.optString("episodeSeparator","#");val fs=c.optString("fieldSeparator","$");require(es.isNotEmpty()&&fs.isNotEmpty())
                val chapters=road.split(es).take(3000).mapIndexedNotNull{j,item->val at=item.indexOf(fs);if(at<0)null else chapter(item.take(at),item.substring(at+fs.length),i,j)}
                if(chapters.isEmpty())null else Line(names.getOrNull(i).orEmpty().ifBlank{"线路 ${i+1}"},chapters)
            }
        } else (if(c.optString("roadsPath","$.data.roads[*]").isBlank())listOfNotNull(root) else JsonRulePath.values(root,c.optString("roadsPath","$.data.roads[*]"))).take(30).mapIndexedNotNull{i,road->
            val chapters=JsonRulePath.values(road,c.optString("episodesPath","$.episodes[*]")).take(3000).mapIndexedNotNull{j,ch->chapter(JsonRulePath.scalar(ch,c.optString("episodeNamePath","$.name")),JsonRulePath.scalar(ch,c.optString("episodeUrlPath","$.url")),i,j)}
            if(chapters.isEmpty())null else Line(JsonRulePath.scalar(road,c.optString("roadNamePath","$.name")).ifBlank{"线路 ${i+1}"},chapters)
        }
        require(lines.isNotEmpty()){"API规则未提取到剧集，检查规则版本或接口状态"};return Detail(e,lines)
    }
}
private object JsoupText {fun clean(s:String)=org.jsoup.Jsoup.parse(s).text()}
