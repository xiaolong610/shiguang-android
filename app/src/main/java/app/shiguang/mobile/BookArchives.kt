package app.shiguang.mobile

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile

class BookArchive(file:File):AutoCloseable {
    private val zip=ZipFile(file)
    private var expanded=0L
    init{
        try{
        val entries=zip.entries().asSequence().toList()
        require(entries.size<=30000){tr(R.string.ui_0041)}
        require(entries.map{it.name}.distinct().size==entries.size){tr(R.string.ui_0042)}
        entries.forEach{safe(it.name);require(it.size<=400L*1024*1024){tr(R.string.ui_0043)}}
        }catch(e:Exception){zip.close();throw e}
    }
    val names get()=zip.entries().asSequence().filter{!it.isDirectory}.map{it.name}.toList()
    fun has(name:String)=zip.getEntry(safe(name))!=null
    fun stream(name:String):InputStream {
        val entry=zip.getEntry(safe(name))?:error(tr(R.string.ui_0044, name.take(100)))
        val input=zip.getInputStream(entry)
        return object:java.io.FilterInputStream(input){
            private fun track(n:Int):Int{if(n>0){expanded+=n;require(expanded<=400L*1024*1024){tr(R.string.ui_0045)}};return n}
            override fun read()=super.read().also{if(it>=0)track(1)}
            override fun read(b:ByteArray,off:Int,len:Int):Int=track(`in`.read(b,off,len))
        }
    }
    fun xml(name:String)=stream(name).use{Jsoup.parse(String(it.readBytesLimited(4*1024*1024),Charsets.UTF_8),"",Parser.xmlParser())}
    override fun close(){zip.close()}
    companion object {
        fun safe(name:String):String{require(name.isNotBlank()&&!name.startsWith('/')&&!name.contains('\\')&&!name.contains(':')&&!name.split('/').contains("..")){tr(R.string.ui_0046)};return name}
        fun resolve(base:String,href:String):String {
            require(!href.contains('\\')){tr(R.string.ui_0047)}
            val u=URI("https://book.local/$base").resolve(href)
            require(u.host=="book.local"&&u.scheme=="https"&&u.rawQuery==null){tr(R.string.ui_0048)}
            return safe(u.path.removePrefix("/"))
        }
    }
}

object BookEpub {
    private fun local(root:org.jsoup.nodes.Element,name:String)=root.getAllElements().filter{it.tagName().substringAfter(':').equals(name,true)}
    fun read(file:File,sink:BookStorage){BookArchive(file).use{z->
        require(z.has("META-INF/container.xml")){tr(R.string.ui_0049)}
        val opf=local(z.xml("META-INF/container.xml"),"rootfile").firstOrNull()?.attr("full-path").orEmpty()
        require(opf.isNotBlank()){tr(R.string.ui_0050)}
        if(z.has("META-INF/encryption.xml")){
            val methods=local(z.xml("META-INF/encryption.xml"),"EncryptionMethod").map{it.attr("Algorithm")}
            require(methods.all{it=="http://www.idpf.org/2008/embedding"||it=="http://ns.adobe.com/pdf/enc#RC"}){tr(R.string.ui_0051)}
        }
        val doc=z.xml(opf)
        sink.bookTitle=local(doc,"title").firstOrNull()?.text().orEmpty()
        sink.author=local(doc,"creator").firstOrNull()?.text().orEmpty()
        val items=local(doc,"manifest").flatMap{local(it,"item")}.associateBy{it.attr("id")}
        val labels=mutableMapOf<String,String>()
        items.values.filter{it.attr("properties").split(' ').contains("nav")}.forEach{nav->
            val path=BookArchive.resolve(opf,nav.attr("href"));local(z.xml(path),"nav").flatMap{local(it,"a")}.filter{it.hasAttr("href")}.forEach{a->labels.putIfAbsent(BookArchive.resolve(path,a.attr("href")),a.text())}
        }
        items.values.filter{it.attr("media-type")=="application/x-dtbncx+xml"}.forEach{ncx->
            val path=BookArchive.resolve(opf,ncx.attr("href"));local(z.xml(path),"navPoint").forEach{n->local(n,"content").firstOrNull{it.hasAttr("src")}?.let{labels.putIfAbsent(BookArchive.resolve(path,it.attr("src")),local(n,"navLabel").firstOrNull()?.text().orEmpty())}}
        }
        val order=local(doc,"spine").flatMap{local(it,"itemref")}.filter{it.attr("linear")!="no"}
        require(order.isNotEmpty()){tr(R.string.ui_0052)}
        order.forEachIndexed{i,ref->
            val item=items[ref.attr("idref")]?:error(tr(R.string.ui_0053))
            require(item.attr("media-type") in setOf("application/xhtml+xml","text/html")){tr(R.string.ui_0054)}
            val path=BookArchive.resolve(opf,item.attr("href"));sink.startChapter(labels[path].orEmpty().ifBlank{tr(R.string.ui_0055, i+1)})
            z.stream(path).bufferedReader(Charsets.UTF_8).use{BookMarkup.read(it,sink)}
        }
    }}
}
