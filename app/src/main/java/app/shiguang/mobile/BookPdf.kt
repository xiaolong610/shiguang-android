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
            require(!doc.isEncrypted){tr(R.string.ui_0065)}
            sink.bookTitle=doc.documentInformation.title.orEmpty();sink.author=doc.documentInformation.author.orEmpty()
            require(doc.numberOfPages<=20000){tr(R.string.ui_0066)}
            val stripper=PDFTextStripper().apply{sortByPosition=true}
            for(page in 1..doc.numberOfPages){
                stripper.startPage=page;stripper.endPage=page;sink.startChapter(tr(R.string.ui_0067, page))
                sink.paragraph(stripper.getText(doc))
            }
        }
    }
}
