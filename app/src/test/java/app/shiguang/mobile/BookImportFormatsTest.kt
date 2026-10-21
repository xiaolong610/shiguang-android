package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookImportFormatsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun zip(entries:Map<String,String>):File {
        val file=temp.newFile();ZipOutputStream(file.outputStream()).use{z->entries.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}};return file
    }
    private fun extract(block:(BookStorage)->Unit):Pair<BookStorage,String>{
        val dir=temp.newFolder();val sink=BookStorage(dir);block(sink);val result=sink.finish()
        return sink to (0 until result.second.length()).joinToString("\n"){File(dir,result.second.getJSONObject(it).getString("file")).readText()}
    }
    @Test fun epubReadsSpineOrderMetadataAndEntities(){
        val file=zip(linkedMapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns:dc='urn:dc'><metadata><dc:title>有序小说</dc:title><dc:creator>作者甲</dc:creator></metadata><manifest><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>",
            "OPS/a.xhtml" to "<html><head><script>不要读我</script></head><body><h1>第一章</h1><p>甲&amp;乙</p></body></html>","OPS/b.xhtml" to "<html><body><p>第二段正文</p></body></html>"))
        val (sink,text)=extract{BookEpub.read(file,it)};assertEquals("有序小说",sink.bookTitle);assertEquals("作者甲",sink.author);assertTrue(text.indexOf("甲&乙")<text.indexOf("第二段正文"));assertFalse(text.contains("不要读我"))
    }
    @Test fun encryptedEpubDoesNotPretendToBeReadable(){
        val file=zip(mapOf("META-INF/container.xml" to "<rootfile full-path='x.opf'/>","META-INF/encryption.xml" to "<encryption><EncryptionMethod Algorithm='http://www.w3.org/2001/04/xmlenc#aes256-cbc'/></encryption>"))
        val error=runCatching{extract{BookEpub.read(file,it)}}.exceptionOrNull();assertTrue(error?.message.orEmpty().contains("DRM"))
    }
    @Test fun epubNamespacePrefixesAreNotMistakenForMissingSpine(){
        val file=zip(mapOf("META-INF/container.xml" to "<c:container xmlns:c='urn:c'><c:rootfile full-path='book.opf'/></c:container>","book.opf" to "<o:package xmlns:o='urn:opf' xmlns:d='urn:dc'><o:metadata><d:title>带前缀的书</d:title></o:metadata><o:manifest><o:item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></o:manifest><o:spine><o:itemref idref='a'/></o:spine></o:package>","a.xhtml" to "<x:html xmlns:x='urn:x'><x:body><x:p>前缀正文</x:p></x:body></x:html>"))
        val (sink,text)=extract{BookEpub.read(file,it)};assertEquals("带前缀的书",sink.bookTitle);assertTrue(text.contains("前缀正文"))
    }
    @Test fun zipTraversalAndExternalSpineAreRejected(){
        val bad=zip(mapOf("../escape.txt" to "secret"));assertTrue(runCatching{BookArchive(bad).close()}.isFailure)
        assertTrue(runCatching{BookArchive.resolve("OPS/a.opf","https://outside.example/chapter")}.isFailure)
    }
    @Test fun docxParagraphsUnicodeAndMetadataArePreserved(){
        val file=zip(mapOf("word/document.xml" to "<w:document xmlns:w='urn:w'><w:body><w:p><w:r><w:t>第一章😀</w:t></w:r></w:p><w:p><w:r><w:t>小说正文</w:t><w:tab/><w:t>末尾</w:t></w:r></w:p></w:body></w:document>"))
        val text=extract{BookXml.docx(file,it)}.second;assertTrue(text.contains("第一章😀"));assertTrue(text.contains("小说正文\t末尾"))
    }
    @Test fun fb2SkipsBase64AndUsesSectionTitles(){
        val text="<FictionBook><description><title-info><book-title>长夜</book-title></title-info></description><body><section><title><p>第一章</p></title><p>正文甲</p></section><section><title><p>第二章</p></title><p>正文乙</p></section></body><binary>不要读二进制</binary></FictionBook>"
        val (sink,body)=extract{BookXml.fb2(text.byteInputStream(),it)};assertEquals("长夜",sink.bookTitle);assertTrue(body.contains("正文乙"));assertFalse(body.contains("二进制"))
    }
    @Test fun xmlNeverReadsExternalEntities(){
        val text="<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///not-readable'>]><FictionBook><body><p>&x;</p></body></FictionBook>"
        assertTrue(runCatching{extract{BookXml.fb2(text.byteInputStream(),it)}}.isFailure)
    }
    @Test fun rtfUnicodeHexAndHiddenGroupsAreDecoded(){
        val body=extract{BookRtf.read(StringReader("{\\rtf1\\ansi\\ansicpg1252{\\fonttbl hidden}\\uc1 \\u20013?\\u25991?\\par caf\\'e9 \\{ok\\} {\\*\\generator hidden}end}"),it)}.second
        assertTrue(body.contains("中文"));assertTrue(body.contains("café {ok}"));assertTrue(body.endsWith("end"));assertFalse(body.contains("hidden"))
    }
    @Test fun hugeSingleLineStreamsIntoBoundedChapterFilesWithoutDroppingSurrogates(){
        val value="小说😀abc".repeat(30000)
        val root=temp.newFolder();val sink=BookStorage(root);BookText.read(StringReader(value),sink);val result=sink.finish()
        val chunks=(0 until result.second.length()).map{File(root,result.second.getJSONObject(it).getString("file")).readText()}
        assertEquals(value,chunks.joinToString(""));assertTrue(chunks.size>10);chunks.forEach{assertTrue(it.length<=17000);assertFalse(Character.isHighSurrogate(it.last()));assertFalse(Character.isLowSurrogate(it.first()))}
    }
    @Test fun markdownHeadingsCreateDirectoryAndDoNotConsumeBody(){
        val root=temp.newFolder();val sink=BookStorage(root);BookText.read(StringReader("# 章节甲\n正文一\n## 章节乙\n正文二"),sink,true);val result=sink.finish();assertEquals(2,result.second.length());assertEquals("章节甲",result.second.getJSONObject(0).getString("title"));assertEquals("正文二",File(root,result.second.getJSONObject(1).getString("file")).readText())
    }
    @Test fun htmlHasNoScriptsStylesOrRemoteExecution(){val body=extract{BookMarkup.read(StringReader("<html><head><title>head</title></head><body><p>甲&lt;乙</p><script>hidden</script><style>hidden</style><p>末尾😀</p></body></html>"),it)}.second;assertTrue(body.contains("甲<乙"));assertTrue(body.contains("末尾😀"));assertFalse(body.contains("hidden"));assertFalse(body.contains("head"))}
    @Test fun exactlyOneHundredMiBIsTheDeclaredInputLimit(){assertEquals(104857600L,BookFormats.MAX_BYTES);assertTrue(BookFormats.extensions.containsAll(listOf("epub","mobi","azw3","fb2","pdf","rtf","docx","md","zip")))}
}
