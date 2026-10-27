package app.shiguang.mobile

import java.io.File

object BookMobi {
    init{System.loadLibrary("shiguang_books")}
    private external fun extract(source:String,target:String):String
    fun read(file:File,sink:BookStorage,temp:File):String {
        val out=File(temp,"kindle-html").apply{mkdirs()}
        try{
            val format=extract(file.absolutePath,out.absolutePath)
            out.listFiles().orEmpty().filter{it.extension=="html"}.sortedBy{it.name}.forEachIndexed{i,p->sink.startChapter(tr(R.string.ui_0055, i+1));p.bufferedReader().use{BookMarkup.read(it,sink)}}
            return format
        }catch(e:IllegalArgumentException){
            if(e.message.orEmpty().contains("DRM"))error(tr(R.string.ui_0063))
            throw IllegalArgumentException(tr(R.string.ui_0064, e.message),e)
        }finally{out.deleteRecursively()}
    }
}
