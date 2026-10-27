package app.shiguang.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

enum class Kind(private val labelId: Int) { VIDEO(R.string.ui_0409), COMIC(R.string.ui_0410), NOVEL(R.string.ui_0411); val label:String get()=tr(labelId) }
enum class ContentCategory(private val labelId:Int,val kind:Kind) {
    VIDEO(R.string.ui_0409,Kind.VIDEO),ANIME(R.string.ui_0413,Kind.VIDEO),COMIC(R.string.ui_0410,Kind.COMIC),NOVEL(R.string.ui_0411,Kind.NOVEL);
    val label:String get()=tr(labelId)
    companion object {
        fun fromKind(kind:Kind)=when(kind){Kind.VIDEO->VIDEO;Kind.COMIC->COMIC;Kind.NOVEL->NOVEL}
        fun parse(value:String):ContentCategory?=when(value.lowercase(java.util.Locale.ROOT)){
            "anime","animation","动画","动漫"->ANIME
            "video","movie","影视","电影","电视剧"->VIDEO
            "comic","manga","漫画"->COMIC
            "novel","book","小说"->NOVEL
            else->null
        }
    }
}
data class Source(
    val id: String, val name: String, val kind: Kind, val engine: String,
    val url: String = "", val enabled: Boolean = true, val rules: String = "{}",
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("kind", kind.name.lowercase())
        .put("engine", engine).put("url", url).put("enabled", enabled).put("rules", JSONObject(rules))
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: tr(R.string.ui_0423)
    val category:ContentCategory by lazy(LazyThreadSafetyMode.PUBLICATION){
        if(kind!=Kind.VIDEO)return@lazy ContentCategory.fromKind(kind)
        val r=runCatching{JSONObject(rules)}.getOrElse{JSONObject()}
        val original=r.optJSONObject("original")
        val explicit=sequenceOf(r.optString("category"),r.optString("type"),original?.optString("category").orEmpty(),original?.optString("type").orEmpty()).mapNotNull{ContentCategory.parse(it)}.firstOrNull{it.kind==kind}
        explicit ?: if(engine=="kazumi")ContentCategory.ANIME else ContentCategory.VIDEO
    }
}
data class Entry(
    val id: String, val sourceId: String, val title: String, val kind: Kind,
    val url: String, val cover: String = "", val subtitle: String = "", val description: String = "",
    val category:ContentCategory=ContentCategory.fromKind(kind),
) {
    val key get() = "$sourceId::$id"
    fun json() = JSONObject().put("id",id).put("sourceId",sourceId).put("title",title).put("kind",kind.name)
        .put("url",url).put("cover",cover).put("subtitle",subtitle).put("description",description).put("category",category.name)
    companion object {
        fun parse(o: JSONObject) = Entry(o.getString("id"),o.getString("sourceId"),o.getString("title"),
            Kind.valueOf(o.getString("kind")),o.getString("url"),o.optString("cover"),o.optString("subtitle"),o.optString("description"),
            ContentCategory.parse(o.optString("category"))?.takeIf{it.kind==Kind.valueOf(o.getString("kind"))} ?: ContentCategory.fromKind(Kind.valueOf(o.getString("kind"))))
    }
}
data class Chapter(val title: String, val url: String)
data class Line(val name: String, val chapters: List<Chapter>)
data class Detail(val entry: Entry, val lines: List<Line>) {
    fun json()=JSONObject().put("entry",entry.json()).put("lines",JSONArray().apply {
        lines.forEach {line->put(JSONObject().put("name",line.name).put("chapters",JSONArray().apply {line.chapters.forEach {put(JSONObject().put("title",it.title).put("url",it.url))}}))}
    })
    companion object {
        fun parse(o:JSONObject):Detail {
            val a=o.getJSONArray("lines")
            return Detail(Entry.parse(o.getJSONObject("entry")),(0 until a.length()).map {i->val l=a.getJSONObject(i);val c=l.getJSONArray("chapters");Line(l.getString("name"),(0 until c.length()).map {j->val ch=c.getJSONObject(j);Chapter(ch.getString("title"),ch.getString("url"))})})
        }
    }
}
data class PageContent(val text: String = "", val images: List<String> = emptyList())
data class Progress(val entry: Entry, val line: Int, val chapter: Int, val offset: Long, val updatedAt: Long, val chapterTitle:String="")
data class SearchReport(val entries: List<Entry>, val errors: List<String>, val searched: Int)

object SourceCodec {
    fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun httpUrl(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { tr(R.string.ui_0424) }
        return url.trim()
    }
    fun parse(text: String): List<Source> {
        val raw = text.trim().removePrefix("\uFEFF")
        val array = when {
            raw.startsWith("[") -> JSONArray(raw)
            raw.startsWith("{") -> JSONObject(raw).let { it.optJSONArray("sources") ?: JSONArray().put(it) }
            else -> JSONArray().put(JSONObject().put("name", URI(httpUrl(raw)).host).put("engine","maccms").put("url",raw))
        }
        require(array.length() in 1..500) { tr(R.string.ui_0425) }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val engine = o.optString("engine").ifBlank { if(o.has("baseURL") && (o.has("searchList") || o.has("searchMode"))) "kazumi" else if(o.has("homepageUrl")||o.has("bookSourceName")) "ficket" else if(o.has("api")) "maccms" else "html" }
            require(engine in setOf("maccms","html","kazumi","ficket")) { tr(R.string.ui_0426, engine) }
            val inferredKind=when{ o.has("contentRegex")->"novel";o.has("comicImageUrlListsRegex")||o.has("bookSourceName")->"comic";else->"video" }
            val rawKind=o.optString("kind",inferredKind)
            val kind = if(rawKind.equals("anime",true))Kind.VIDEO else runCatching { Kind.valueOf(rawKind.uppercase()) }.getOrElse { error(tr(R.string.ui_0427)) }
            require(engine != "maccms" || kind == Kind.VIDEO) { tr(R.string.ui_0428) }
            val url = httpUrl(o.optString("url").ifBlank { if(engine=="kazumi") o.optString("baseURL") else if(engine=="ficket")o.optString("homepageUrl").ifBlank{o.optString("bookSourceUrl")} else o.optString("api") })
            val rules = o.optJSONObject("rules") ?: if(engine=="kazumi"||engine=="ficket") JSONObject(o.toString()) else JSONObject()
            if(rawKind.equals("anime",true))rules.put("category","anime")
            else if(o.optString("category").isNotBlank())rules.put("category",o.getString("category"))
            if(engine=="kazumi") KazumiRules.validate(rules)
            if(engine=="ficket")FicketRules.validate(rules)
            val original=rules.optJSONObject("original")
            val adNote=o.optString("tip").ifBlank{original?.optString("tip").orEmpty()}
            val knownAds=rules.optBoolean("knownAds") || o.optBoolean("adFilterEnabled") || original?.optBoolean("adFilterEnabled")==true || adNote.contains("广告") || AdSourceHints.known(url)
            if(knownAds){rules.put("knownAds",true);if(adNote.isNotBlank())rules.put("adSourceNote",adNote)}
            if(engine == "html") {
                require(!rules.optString("searchUrl").isBlank() && !rules.optString("list").isBlank() && !rules.optString("title").isBlank()) { tr(R.string.ui_0430) }
                require(!rules.optString("chapters").isBlank()) { tr(R.string.ui_0431) }
                require(!rules.optString(if(kind == Kind.COMIC) "images" else if(kind == Kind.NOVEL) "content" else "media").isBlank()) { tr(R.string.ui_0432) }
            }
            val name=o.optString("name").ifBlank{o.optString("bookSourceName")}.ifBlank { URI(url).host }.take(80)
            val savedId=o.optString("id").takeIf{o.has("engine")&&it.matches(Regex("[a-f0-9]{24}"))}
            val originalEnabled=if(engine=="ficket")!o.optBoolean("disabled")&&o.optBoolean("enable",true)&&o.optBoolean("activeTag",true) else !knownAds
            Source(savedId ?: stableId(if(engine=="kazumi") "kazumi|${name.lowercase(java.util.Locale.ROOT)}" else "$engine|$kind|$url"),name,kind,engine,url,o.optBoolean("enabled",originalEnabled),rules.toString())
        }.distinctBy { it.id }
    }
}

object MacParser {
    fun entries(source: Source, json: String): List<Entry> {
        val root = JSONObject(json)
        if(root.has("code") && root.optInt("code") == 0) error(root.optString("msg",tr(R.string.ui_0433)))
        val list = root.optJSONArray("list") ?: error(tr(R.string.ui_0434))
        return (0 until list.length()).take(100).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("vod_id")
            if(id.isBlank()) null else Entry(id,source.id,org.jsoup.Jsoup.parse(o.optString("vod_name")).text(),Kind.VIDEO,id,
                o.optString("vod_pic"),o.optString("vod_remarks").ifBlank { o.optString("type_name") },org.jsoup.Jsoup.parse(o.optString("vod_content")).text())
        }
    }
    fun detail(entry: Entry, json: String): Detail {
        val o = JSONObject(json).optJSONArray("list")?.optJSONObject(0) ?: error(tr(R.string.ui_0435))
        val names = o.optString("vod_play_from").split("$$$")
        val urls = o.optString("vod_play_url").split("$$$")
        val lines = urls.mapIndexedNotNull { index, group ->
            val chapters = group.split('#').mapNotNull { item ->
                val split = item.indexOf('$')
                if(split < 0) null else Chapter(item.take(split).ifBlank { tr(R.string.ui_0241) },item.substring(split+1).trim()).takeIf { it.url.startsWith("http://") || it.url.startsWith("https://") }
            }
            if(chapters.isEmpty()) null else Line(names.getOrNull(index)?.ifBlank { null } ?: tr(R.string.ui_0299, index+1), chapters)
        }
        require(lines.isNotEmpty()) { tr(R.string.ui_0438) }
        return Detail(entry.copy(description=org.jsoup.Jsoup.parse(o.optString("vod_content")).text()),lines)
    }
}
