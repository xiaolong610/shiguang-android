package app.shiguang.mobile

import android.content.Context
import java.io.File

object BookFormats {
    const val MAX_BYTES=100L*1024*1024
    val extensions=setOf("txt","text","epub","fb2","html","htm","xhtml","md","markdown","rtf","docx","pdf","mobi","azw","azw3","prc","zip")
    val LABEL get()=tr(R.string.ui_0056)
    fun read(context:Context,file:File,name:String,sink:BookStorage,temp:File,allowZip:Boolean=true){
        val ext=name.substringAfterLast('.',"").lowercase()
        sink.inputFormat=ext
        when(ext){
            "txt","text","md","markdown"->BookText.reader(file).use{BookText.read(it,sink,ext=="md"||ext=="markdown")}
            "epub"->BookEpub.read(file,sink)
            "html","htm","xhtml"->BookText.reader(file).use{BookMarkup.read(it,sink)}
            "fb2"->file.inputStream().use{BookXml.fb2(it,sink)}
            "docx"->BookXml.docx(file,sink)
            "rtf"->file.reader(Charsets.ISO_8859_1).use{BookRtf.read(it,sink)}
            "pdf"->BookPdf.read(context,file,sink)
            "mobi","azw","azw3","prc"->sink.inputFormat=BookMobi.read(file,sink,temp)
            "zip"->{
                require(allowZip){tr(R.string.ui_0057)}
                BookArchive(file).use{zip->
                    val candidates=zip.names.filter{!it.startsWith("__MACOSX/")&&!it.substringAfterLast('/').startsWith('.')&&it.substringAfterLast('.',"").lowercase() in extensions-"zip"}
                    require(candidates.size==1){tr(R.string.ui_0058)}
                    val child=File(temp,"unpacked-book");zip.stream(candidates.single()).use{input->child.outputStream().use{output->
                        val b=ByteArray(65536);var n: Int;var size=0L
                        while(input.read(b).also{n=it}>=0){size+=n;require(size<=MAX_BYTES){tr(R.string.ui_0059)};output.write(b,0,n)}
                    }}
                    try{read(context,child,candidates.single(),sink,temp,false)}finally{child.delete()}
                }
            }
            else->error(tr(R.string.ui_0060, LABEL))
        }
    }
}
