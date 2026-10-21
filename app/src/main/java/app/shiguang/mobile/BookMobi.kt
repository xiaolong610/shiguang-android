package app.shiguang.mobile

import java.io.File

object BookMobi {
    init{System.loadLibrary("shiguang_books")}
    private external fun extract(source:String,target:String):String
    fun read(file:File,sink:BookStorage,temp:File):String {
        val out=File(temp,"kindle-html").apply{mkdirs()}
        try{
            val format=extract(file.absolutePath,out.absolutePath)
            out.listFiles().orEmpty().filter{it.extension=="html"}.sortedBy{it.name}.forEachIndexed{i,p->sink.startChapter("第${i+1}节");p.bufferedReader().use{BookMarkup.read(it,sink)}}
            return format
        }catch(e:IllegalArgumentException){
            if(e.message.orEmpty().contains("DRM"))error("此Kindle电子书受DRM保护，无法导入")
            throw IllegalArgumentException("无法解析MOBI/AZW3电子书：${e.message}",e)
        }finally{out.deleteRecursively()}
    }
}
