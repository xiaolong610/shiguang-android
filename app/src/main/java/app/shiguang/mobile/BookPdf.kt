package app.shiguang.mobile

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object BookPdf {
    fun read(context:Context,file:File,sink:BookStorage){
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly()).use{doc->
            require(!doc.isEncrypted){"此PDF受密码或加密保护，请先取得未加密文件"}
            sink.bookTitle=doc.documentInformation.title.orEmpty();sink.author=doc.documentInformation.author.orEmpty()
            require(doc.numberOfPages<=20000){"PDF页数过多，请分卷导入"}
            val stripper=PDFTextStripper().apply{sortByPosition=true}
            for(page in 1..doc.numberOfPages){
                stripper.startPage=page;stripper.endPage=page;sink.startChapter("第${page}页")
                sink.paragraph(stripper.getText(doc))
            }
        }
    }
}
