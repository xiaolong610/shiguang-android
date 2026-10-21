package app.shiguang.mobile

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

@RunWith(AndroidJUnit4::class)
class BookFormatsDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var lastInputFormat=""
    private fun document(name:String,write:(java.io.OutputStream)->Unit,check:(android.net.Uri)->Unit){
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")})?:error("无法创建文档")
        try{resolver.openOutputStream(uri)!!.use(write);check(uri)}finally{resolver.delete(uri,null,null)}
    }
    private fun zip(entries:Map<String,String>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->entries.forEach{(n,t)->z.putNextEntry(ZipEntry(n));z.write(t.toByteArray());z.closeEntry()}}}.toByteArray()
    private fun importedText(name:String,bytes:ByteArray):String {
        var value=""
        document(name,{it.write(bytes)}){uri->runBlocking{
            val books=LocalNovels(context);val entry=books.import(uri);val detail=books.detail(entry)
            lastInputFormat=JSONObject(File(context.filesDir,"novels/${entry.id}.json").readText()).optString("inputFormat")
            value=detail.lines.single().chapters.joinToString("\n"){books.page(entry,it).text}
        }}
        return value
    }
    @Test fun epubFb2DocxHtmlMarkdownRtfAndZipAreReadableOffline(){
        val results=linkedMapOf<String,Boolean>()
        val epub=zip(mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>","book.opf" to "<package><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>","a.xhtml" to "<html><body><h1>第一章</h1><p>电子书格式验证正文甲😀。</p></body></html>"))
        val formats=linkedMapOf(
            "验证.epub" to epub,
            "验证.fb2" to "<FictionBook><body><section><title><p>第一章</p></title><p>电子书格式验证正文甲😀。</p></section></body></FictionBook>".toByteArray(),
            "验证.docx" to zip(mapOf("word/document.xml" to "<w:document xmlns:w='urn:w'><w:body><w:p><w:r><w:t>电子书格式验证正文甲😀。</w:t></w:r></w:p></w:body></w:document>")),
            "验证.html" to "<html><body><p>电子书格式验证正文甲😀。</p></body></html>".toByteArray(),
            "验证.md" to "# 第一章\n电子书格式验证正文甲😀。".toByteArray(),
            "验证.rtf" to "{\\rtf1\\ansi\\uc1 \\u20013?\\u25991?RTF_TEXT_END}".toByteArray(),
            "验证.zip" to zip(mapOf("小说.txt" to "第一章\n电子书格式验证正文甲😀。")))
        formats.forEach{(name,bytes)->val text=importedText(name,bytes);assertTrue("$name 正文丢失",if(name.endsWith("rtf"))text.contains("中文RTF_TEXT_END")else text.contains("电子书格式验证正文甲😀。"));results[name]=true}
        File(context.getExternalFilesDir(null),"book-formats-result.json").writeText(JSONObject(results as Map<*,*>).toString(2))
    }
    @Test fun pdfTextPagesImportAndCanBeNarrated(){
        PDFBoxResourceLoader.init(context)
        val bytes=ByteArrayOutputStream().also{out->PDDocument().use{doc->repeat(2){i->val page=PDPage();doc.addPage(page);PDPageContentStream(doc,page).use{s->s.beginText();s.setFont(PDType1Font.HELVETICA,16f);s.newLineAtOffset(30f,700f);s.showText("Shiguang PDF chapter ${i+1} readable text.");s.endText()}};doc.save(out)}}.toByteArray()
        val text=importedText("验证.pdf",bytes);assertTrue(text.contains("chapter 1"));assertTrue(text.contains("chapter 2"));assertFalse(text.contains("%PDF"))
    }
    @Test fun actualMobiHuffdicAndKf8AreDecodedAndDrmIsRejected(){
        val result=JSONObject()
        for((fixture,name,expected) in listOf(Triple("sample-cp1252.mobi","旧版验证.mobi","MOBI"),Triple("sample-unicode-huffdic.mobi","验证.mobi","KF8"),Triple("sample-obfuscated-fonts.mobi","验证.azw3","KF8"))){
            val bytes=instrumentation.context.assets.open("books/$fixture").use{it.readBytes()}
            val text=importedText(name,bytes);assertTrue("$name 未得到正文",text.length>100);assertFalse(text.contains("<html"));assertEquals(expected,lastInputFormat);result.put(name,JSONObject().put("characters",text.length).put("decoded",true).put("actualFormat",lastInputFormat))
        }
        val protected=instrumentation.context.assets.open("books/sample-drm-v1.mobi").use{it.readBytes()}
        val failure=runCatching{importedText("加密验证.mobi",protected)}.exceptionOrNull();assertTrue(failure?.message.orEmpty().contains("DRM"));result.put("drmRejected",true)
        File(context.getExternalFilesDir(null),"kindle-import-result.json").writeText(result.toString(2))
    }
    @Test fun exactly100MiBImportsWithoutWholeBookHeapAnd101MiBIsRejected(){
        val inputBytes=BookFormats.MAX_BYTES;var imported:Entry?=null
        val prefix="第一章 大文件\nSTART100\n".toByteArray();val suffix="\nEND100".toByteArray()
        val started=System.currentTimeMillis()
        try{
            document("100MiB验证.txt",{out->
                out.write(prefix);val block=ByteArray(65536){97};var remaining=inputBytes-prefix.size-suffix.size
                while(remaining>0){val size=minOf(remaining,block.size.toLong()).toInt();out.write(block,0,size);remaining-=size};out.write(suffix)
            }){uri->runBlocking{
                val books=LocalNovels(context);val entry=books.import(uri);imported=entry;val chapters=books.detail(entry).lines.single().chapters
                assertTrue(chapters.size>8000);assertTrue(books.page(entry,chapters.first()).text.startsWith("START100"));assertTrue(books.page(entry,chapters.last()).text.endsWith("END100"))
                val meta=JSONObject(File(context.filesDir,"novels/${entry.id}.json").readText());assertEquals(2,meta.getInt("format"));assertFalse(meta.getJSONArray("chapters").getJSONObject(0).has("text"))
                File(context.getExternalFilesDir(null),"book-100mib-result.json").writeText(JSONObject().put("inputBytes",inputBytes).put("chapters",chapters.size).put("firstAndLastTextVerified",true).put("chapterFiles",true).put("elapsedMs",System.currentTimeMillis()-started).toString(2))
            }}
            document("101MiB拒绝验证.txt",{out->val block=ByteArray(65536){97};repeat(101*16){out.write(block)}}){uri->val failure=runCatching{runBlocking{LocalNovels(context).import(uri)}}.exceptionOrNull();assertTrue(failure?.message.orEmpty().contains("100MiB"))}
        }finally{imported?.let{File(context.filesDir,"novels/${it.id}.json").delete();File(context.filesDir,"novels/${it.id}").deleteRecursively()}}
    }
    @Test fun legacyImportedBooksRemainReadable(){
        val id=SourceCodec.stableId("legacy-format-test");val entry=Entry(id,LocalNovels.source.id,"旧版书籍",Kind.NOVEL,"local:$id")
        val file=File(context.filesDir,"novels/$id.json");file.parentFile!!.mkdirs();file.writeText(JSONObject().put("entry",entry.json()).put("chapters",org.json.JSONArray().put(JSONObject().put("title","旧章").put("text","旧书正文"))).toString())
        try{val store=LocalNovels(context);assertEquals("旧书正文",store.page(entry,store.detail(entry).lines.single().chapters.single()).text)}finally{file.delete()}
    }
}
