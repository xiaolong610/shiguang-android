package app.shiguang.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.lastOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ContentRepository(private val appContext:android.content.Context?=null) {
    val client:OkHttpClient get()=ContentNetwork.foreground
    suspend fun fetch(url: String, headers: Map<String,String> = emptyMap(), maxBytes: Int = 4*1024*1024, method:String="GET", body:String?=null, contentType:String="application/json"): String = withContext(Dispatchers.IO) {
        SourceCodec.httpUrl(url)
        val request=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        headers.forEach { (key,value) -> request.header(key,value) }
        if(method=="POST")request.post((body ?: "").toRequestBody(contentType.toMediaType()))
        val lane=if(currentCoroutineContext()[SearchTraffic.Key]!=null)ContentNetwork.search else client
        val call=lane.newCall(request.build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){
                    try{val text=decode(response,maxBytes);if(continuation.isActive)continuation.resume(text)}
                    catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                }
            })
        }
    }
    private fun decode(response:Response,maxBytes:Int):String = response.use { response ->
            if(!response.isSuccessful) error("HTTP ${response.code}")
            val body=response.body ?: error("服务器返回了空内容")
            require(body.contentLength() <= maxBytes || body.contentLength() == -1L) { "内容超过大小限制" }
            val bytes=body.byteStream().use { it.readBytesLimited(maxBytes) }
            require(bytes.size<=maxBytes) { "内容超过大小限制" }
            val declared=body.contentType()?.charset()
            val meta=if(declared==null)Regex("(?i)charset\\s*=\\s*['\"]?([A-Za-z0-9_-]+)").find(String(bytes,0,minOf(bytes.size,4096),Charsets.ISO_8859_1))?.groupValues?.get(1)?.let{runCatching{charset(it)}.getOrNull()}else null
            String(bytes,declared ?: meta ?: Charsets.UTF_8)
    }
    fun headers(s: Source): Map<String,String> {
        val r=JSONObject(s.rules);val h=r.optJSONObject("headers") ?: JSONObject()
        val result=h.keys().asSequence().associateWith { h.getString(it) }.toMutableMap()
        if(s.engine=="kazumi") {
            if(r.optString("referer").isNotBlank())result["Referer"]=r.getString("referer")
            result["User-Agent"]=r.optString("userAgent").ifBlank{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"}
        }
        if(s.engine=="ficket") {
            val o=FicketRules.original(s)
            result.putIfAbsent("Referer",s.url)
            o.optString("httpUserAgent").takeIf{it.isNotBlank()}?.let{result["User-Agent"]=it}
        }
        return result
    }
    private suspend fun kazumiFetch(s:Source,search:Boolean,q:String="",source:String=""):String {
        val r=JSONObject(s.rules)
        if(r.optString(if(search)"searchMode" else "chapterMode","xpath")=="api") {
            val request=KazumiApi.request(s,search,q,source)
            return fetch(request.url,headers(s)+request.headers,method=request.method,body=request.body,contentType=request.contentType)
        }
        val target=if(search)KazumiRules.searchUrl(s,q) else source
        if(search&&r.optBoolean("usePost")) {
            val u=target.toHttpUrl();return fetch(u.newBuilder().query(null).build().toString(),headers(s),method="POST",body=u.encodedQuery,contentType="application/x-www-form-urlencoded")
        }
        return fetch(target,headers(s))
    }
    private fun api(s: Source, vararg params: Pair<String,String>): String {
        val b=s.url.toHttpUrl().newBuilder()
        params.forEach { b.setQueryParameter(it.first,it.second) }
        return b.build().toString()
    }
    fun searchUpdates(sources:List<Source>,kind:Kind,query:String,onOutcome:suspend(SearchOutcome)->Unit={})=
        SearchEngine().updates(sources.filter{it.enabled&&it.kind==kind},query,onOutcome){s,q->searchOne(s,q)}
    suspend fun search(sources: List<Source>,kind: Kind,query: String): SearchReport {
        val update=searchUpdates(sources,kind,query).lastOrNull()?:SearchUpdate(emptyList())
        return SearchReport(update.entries,update.errors,update.outcomes.size)
    }
    suspend fun searchOne(s: Source,q: String): List<Entry> = withContext(Dispatchers.Default){(when(s.engine) {
        "demo" -> Demo.entries.filter { it.sourceId==s.id && (q.isBlank() || it.title.contains(q,true) || it.subtitle.contains(q,true)) }
        "maccms" -> MacParser.entries(s,fetch(api(s,"ac" to "detail",*(if(q.isBlank()) emptyArray() else arrayOf("wd" to q))),headers(s)))
        "ficket" -> FicketRepository(this@ContentRepository,appContext).search(s,q)
        "kazumi" -> {val raw=kazumiFetch(s,true,q);withContext(Dispatchers.Default){if(JSONObject(s.rules).optString("searchMode")=="api")KazumiApi.search(s,raw) else KazumiRules.search(s,raw,KazumiRules.searchUrl(s,q))}}
        else -> {
            val r=JSONObject(s.rules)
            val template=r.getString("searchUrl")
            val target=resolve(s.url,template.replace("{query}",URLEncoder.encode(q,"UTF-8")))
            withContext(Dispatchers.Default) {
                val doc=Jsoup.parse(fetch(target,headers(s)),target)
                doc.select(r.getString("list")).take(100).mapNotNull { el ->
                    val titleEl=el.selectFirst(r.getString("title")) ?: return@mapNotNull null
                    val title=titleEl.text().ifBlank { titleEl.attr("title") }
                    val link=if(r.optString("link").isBlank()) titleEl else el.selectFirst(r.getString("link"))
                    val href=link?.attr(r.optString("linkAttr","href"))?.takeIf{it.isNotBlank() && !it.startsWith('#')}?.let { resolve(target,it) } ?: ""
                    if(title.isBlank() || !isHttp(href)) null else Entry(SourceCodec.stableId(href),s.id,title,s.kind,href,
                        r.optString("cover").takeIf { it.isNotBlank() }?.let { el.selectFirst(it)?.attr(r.optString("coverAttr","src")) }?.takeIf{it.isNotBlank()}?.let { resolve(target,it) } ?: "",
                        r.optString("subtitle").takeIf { it.isNotBlank() }?.let { el.selectFirst(it)?.text() } ?: "")
                }
            }
        }
    }
    ).map{it.copy(category=s.category)}}
    suspend fun detail(s:Source,e:Entry,force:Boolean=false):Detail {
        val key=SearchPlanner.fingerprint(s)+"|"+e.key+"|"+e.url
        if(!force)DetailMemoryCache.shared.get(key)?.let{return it}
        return withContext(Dispatchers.Default){detailUncached(s,e)}.also{DetailMemoryCache.shared.put(key,it)}
    }
    private suspend fun detailUncached(s: Source,e: Entry): Detail = when(s.engine) {
        "local" -> withContext(Dispatchers.IO){LocalNovels(appContext?:error("无法打开本地书籍")).detail(e)}
        "demo" -> Demo.detail(e)
        "direct" -> Detail(e,listOf(Line("视频链接",listOf(Chapter("播放",e.url)))))
        "maccms" -> MacParser.detail(e,fetch(api(s,"ac" to "detail","ids" to e.id),headers(s)))
        "ficket" -> FicketRepository(this,appContext).detail(s,e)
        "kazumi" -> {val raw=kazumiFetch(s,false,source=e.url);withContext(Dispatchers.Default){if(JSONObject(s.rules).optString("chapterMode")=="api")KazumiApi.detail(s,e,raw) else KazumiRules.detail(s,e,raw)}}
        else -> {
            val r=JSONObject(s.rules)
            val doc=Jsoup.parse(fetch(e.url,headers(s)),e.url)
            val chapters=doc.select(r.getString("chapters")).take(3000).mapNotNull { el ->
                val href=el.attr(r.optString("chapterAttr","href"))
                if(href.isBlank() || href.startsWith('#'))return@mapNotNull null
                val url=resolve(e.url,href)
                if(isHttp(url)) Chapter(el.text().ifBlank { el.attr("title") }.ifBlank { "未命名章节" },url) else null
            }.distinctBy { it.url }
            require(chapters.isNotEmpty()) { "未提取到章节，请检查源的章节选择器" }
            val description=r.optString("description").takeIf { it.isNotBlank() }?.let { doc.select(it).text() } ?: e.description
            Detail(e.copy(description=description),listOf(Line("章节",if(r.optBoolean("reverseChapters")) chapters.reversed() else chapters)))
        }
    }
    suspend fun readingPage(s:Source,e:Entry,chapter:Chapter,force:Boolean=false):PageContent = ReadingPageCache.shared.load(
        SourceCodec.stableId(s.json().toString()+"|"+e.key+"|"+chapter.url),force){page(s,e,chapter)}
    suspend fun page(s:Source,e:Entry,chapter:Chapter):PageContent=withContext(Dispatchers.Default){pageUncached(s,e,chapter)}
    private suspend fun pageUncached(s: Source,e: Entry,chapter: Chapter): PageContent {
        if(s.engine=="local")return withContext(Dispatchers.IO){LocalNovels(appContext?:error("无法打开本地书籍")).page(e,chapter)}
        if(s.engine=="demo") return Demo.page(e,chapter)
        if(s.engine=="ficket")return FicketRepository(this,appContext).page(s,e,chapter)
        val r=JSONObject(s.rules)
        val doc=Jsoup.parse(fetch(chapter.url,headers(s)),chapter.url)
        return if(e.kind==Kind.COMIC) {
            val images=doc.select(r.getString("images")).map { it.attr(r.optString("imageAttr","src")) }.filter{it.isNotBlank()}.map{resolve(chapter.url,it)}.filter { isHttp(it) }.distinct()
            require(images.isNotEmpty()) { "未提取到图片；首版网页源只解析服务器返回的 HTML" }
            PageContent(images=images)
        } else {
            val body=doc.select(r.getString("content"))
            require(body.isNotEmpty()) { "未提取到正文，请检查正文选择器" }
            body.select("script,style,iframe,button").remove()
            body.select("br").forEach { it.before("\\n") }
            body.select("p,div").forEach { it.prepend("\\n") }
            val text=body.text().replace("\\n","\n").split('\n').map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
            require(text.isNotBlank()) { "章节正文为空" }
            PageContent(text=text)
        }
    }
    suspend fun video(s: Source,ch: Chapter): String {
        if(s.engine=="demo" && ch.url=="asset:///sample.mp4") return ch.url
        if(s.engine != "html") return SourceCodec.httpUrl(ch.url)
        val r=JSONObject(s.rules)
        val doc=Jsoup.parse(fetch(ch.url,headers(s)),ch.url)
        val el=doc.selectFirst(r.getString("media")) ?: error("未找到视频地址，可用浏览器打开原页面")
        return SourceCodec.httpUrl(resolve(ch.url,el.attr(r.optString("mediaAttr","src"))))
    }
    companion object {
        fun isHttp(url: String) = runCatching { SourceCodec.httpUrl(url) }.isSuccess
        fun resolve(base: String,value: String): String = runCatching { URI(base).resolve(value.trim()).toString() }.getOrDefault("")
        fun friendlyError(t: Throwable): String = when(t) {
            is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
            is java.net.UnknownHostException -> "无法连接网站，请检查网络或源地址"
            is javax.net.ssl.SSLException -> "网站证书验证失败"
            else -> t.message?.take(160) ?: "加载失败"
        }
    }
}

object Demo {
    val sources=Kind.entries.map { Source("demo-${it.name}","拾光示例 · ${it.label}",it,"demo") }
    val entries=listOf(
        Entry("light","demo-VIDEO","山海之间",Kind.VIDEO,"demo:video",subtitle="原创光影 · 离线播放示例",description="一段十二秒的流动光影，配上轻柔的合成音。用于测试原生视频播放、横竖屏、倍速和播放进度，不需要联网。"),
        Entry("night","demo-COMIC","月亮便利店",Kind.COMIC,"demo:comic",subtitle="原创短篇 · 离线阅读",description="一个深夜，一家还亮着灯的便利店，一封没有地址的信。拾光自带的原创示例漫画，三页一话，可离线阅读。"),
        Entry("letter","demo-NOVEL","把今天寄给明天",Kind.NOVEL,"demo:novel",subtitle="原创故事 · 三章完结",description="海边小镇的邮局里，有一个只接收寄给未来的信箱。原创示例故事，可离线阅读。")
    )
    fun detail(e: Entry): Detail = Detail(e,listOf(Line(if(e.kind==Kind.VIDEO) "视频示例" else "目录",when(e.kind) {
        Kind.VIDEO -> listOf(Chapter("十二秒的光","asset:///sample.mp4"))
        Kind.COMIC -> listOf(Chapter("第 1 话 · 今晚也有光","demo:comic:1"))
        Kind.NOVEL -> listOf(Chapter("第一章 · 蓝色信箱","demo:novel:1"),Chapter("第二章 · 慢一点的时间","demo:novel:2"),Chapter("第三章 · 明天见","demo:novel:3"))
    })))
    fun page(e: Entry,ch: Chapter): PageContent = if(e.kind==Kind.COMIC) PageContent(images=(1..3).map { "file:///android_asset/comic-$it.svg" }) else PageContent(text=when(ch.url.last()) {
        '1' -> "海边的邮局在下午四点关门。\n\n林夏第一天上班，发现门外除了红色的邮筒，还有一个蓝色信箱。漆已经掉了，箱口贴着一行手写的小字：寄给以后的自己。\n\n她问老周，这个箱子多久收一次信。\n\n老周正拿抹布擦柜台，听见她问，抬头看了一眼墙上的钟。\n\n‘一年。也有人约五年，十年。’\n\n‘那地址变了怎么办？’\n\n‘留电话。有时候，收信的人会自己来找。’\n\n那天下午没什么客人。林夏给一捆明信片盖完邮戳，出去收信。蓝色信箱里躺着三封信，其中一封只写了七个字：等我不再害怕的时候。\n\n她把信拿给老周。老周看了看，放进柜台下面一个木抽屉。抽屉上贴着‘等一等’，字迹和信箱上的一样。\n\n林夏下班时，海水退到了很远的地方。几个孩子提着小桶，弯腰寻找沙地上的贝壳。她在路边坐了一会儿，把手机里那封写了一半又删掉的邮件重新打开。\n\n这一次，她没有删除。\n\n第二天上班前，她绕进文具店，买了一张淡黄色的信纸。纸很薄，迎着太阳，能看见背面的纤维。\n\n她写：明年的今天，你还住在海边吗？\n\n想了想，又添了一句：希望你已经习惯一个人吃晚饭。"
        '2' -> "入秋后，小镇的游客少了。\n\n邮局的玻璃门被风推得吱呀作响。林夏把门挡挪近了些，在柜台上放了一杯热茶。\n\n那天来了一位穿灰色外套的老人。他没带包裹，也没带信，进门后先站在蓝色信箱旁看了一会儿。\n\n‘我来取一封信。大概是去年九月寄的。’\n\n老周核对了名字，拿出一个白色信封。老人接过去，没有马上拆。他问能不能在窗边坐一坐。\n\n林夏给他搬了一把椅子。\n\n老人拆开信，读得很慢。有一会儿，他用手掌压着纸，望向窗外。街对面的面包店刚出炉一批吐司，店员把招牌翻到‘营业中’。\n\n‘是我太太写的。’老人说。\n\n林夏没有问下去。她把热水壶放在他身旁，回到柜台继续整理邮票。\n\n半个小时后，老人起身，把椅子推回原处。他买了两张明信片，说要带回去慢慢写。\n\n午休时，林夏问老周：‘等这么久的信，里面通常写什么？’\n\n‘今天吃了什么，谁家的花开了，想去哪里。’\n\n‘都是这些？’\n\n‘能把这些记下来，就很好了。’\n\n那晚她给自己做了一碗面。煮到一半才发现没有葱，便切了一小把青菜。窗户开着，远处传来轮船的汽笛。她拍了一张照片，认真地写下日期。"
        else -> "春天来的时候，林夏收到了自己的信。\n\n老周把信递给她，说：‘今天你可以早一点走。’\n\n她在海堤上拆开信。淡黄色的纸被折成四折，字有点歪，是她当时趴在窗台上写的。\n\n明年的今天，你还住在海边吗？\n\n她抬头看了看。海还在，堤岸边多了一排新种的树。有一辆自行车停在路灯下，车筐里放着刚买的橘子。那是她的车。\n\n信里问了很多问题。有些已经忘记，有些依然没有答案。她读到最后那句，忽然笑了一下。\n\n希望你已经习惯一个人吃晚饭。\n\n她已经习惯了。有时候一个人，有时候和隔壁面包店的阿宁一起。上周她们试着烤饼干，第一盘烤焦了，第二盘忘记放糖，第三盘才终于像样。\n\n林夏把信装回信封，骑车去文具店。这一次，她选了浅绿色的纸。\n\n店主问要不要多买几张。她说先买一张就好，明年还会再来。\n\n回到家，天还没黑。她把橘子分成两份，一份留在桌上，另一份装进袋子，准备送去隔壁。\n\n写信之前，她先给窗台上的花浇了水。\n\n新信的开头是：\n\n今天过得不错。\n\n（全文完）"
    })
}
