package app.shiguang.mobile

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.File
import javax.xml.parsers.SAXParserFactory

object BookXml {
    private fun parse(input:InputStream,handler:DefaultHandler){
        val factory=SAXParserFactory.newInstance().apply{isNamespaceAware=true}
        for(feature in listOf("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities","http://apache.org/xml/features/nonvalidating/load-external-dtd"))runCatching{factory.setFeature(feature,false)}
        runCatching{factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
        val reader=factory.newSAXParser().xmlReader
        reader.entityResolver=org.xml.sax.EntityResolver{_,_->error("电子书不能引用外部实体")}
        reader.contentHandler=handler;reader.parse(InputSource(input))
    }
    fun docx(file:File,sink:BookStorage){BookArchive(file).use{z->
        require(z.has("word/document.xml")){"不是有效的DOCX文件"}
        if(z.has("docProps/core.xml"))z.xml("docProps/core.xml").let{d->sink.bookTitle=d.getElementsByTag("dc:title").text();sink.author=d.getElementsByTag("dc:creator").text()}
        z.stream("word/document.xml").use{input->parse(input,object:DefaultHandler(){
            private var inText=false
            override fun startElement(uri:String,localName:String,qName:String,a:Attributes){when(localName){"t"->inText=true;"tab"->sink.append("\t");"br","cr"->sink.append("\n")}}
            override fun endElement(uri:String,localName:String,qName:String){when(localName){"t"->inText=false;"p","tr"->sink.append("\n\n")}}
            override fun characters(ch:CharArray,start:Int,length:Int){if(inText)sink.append(String(ch,start,length))}
        })}
    }}
    fun fb2(input:InputStream,sink:BookStorage){parse(input,object:DefaultHandler(){
        private var bodies=0;private var inBinary=false;private var inTitle=false;private val heading=StringBuilder();private var bookTitle=false;private val metadata=StringBuilder()
        override fun startElement(uri:String,localName:String,qName:String,a:Attributes){when(localName){"body"->bodies++;"binary"->inBinary=true;"title"->if(bodies>0){inTitle=true;heading.setLength(0)};"book-title"->{bookTitle=true;metadata.setLength(0)};"empty-line"->if(bodies>0)sink.append("\n\n")}}
        override fun endElement(uri:String,localName:String,qName:String){when(localName){"body"->bodies--;"binary"->inBinary=false;"book-title"->{bookTitle=false;sink.bookTitle=metadata.toString().trim()};"title"->if(inTitle){inTitle=false;sink.startChapter(heading.toString())};"p","section"->if(bodies>0&&!inTitle)sink.append("\n\n")}}
        override fun characters(ch:CharArray,start:Int,length:Int){if(bookTitle&&metadata.length<500)metadata.append(ch,start,length);if(bodies>0&&!inBinary){if(inTitle){if(heading.length<500)heading.append(ch,start,length)}else sink.append(String(ch,start,length))}}
    })}
}
