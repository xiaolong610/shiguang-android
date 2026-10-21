package app.shiguang.mobile

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookFilePickerDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun epubChosenFromTheSystemDocumentPickerOpensInTheReader(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val resolver=instrumentation.targetContext.contentResolver
        val filename="ShiguangBook-${System.nanoTime()}.epub"
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,filename);put(MediaStore.MediaColumns.MIME_TYPE,"application/epub+zip")})?:error("无法创建EPUB文档")
        resolver.openOutputStream(uri)!!.use{out->ZipOutputStream(out).use{z->mapOf("META-INF/container.xml" to "<rootfile full-path='book.opf'/>","book.opf" to "<package xmlns:dc='urn:dc'><metadata><dc:title>手机文件选择验证</dc:title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>","a.xhtml" to "<html><body><p>这是通过系统文件选择器导入的EPUB正文。</p></body></html>").forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}}}
        fun find(node:android.view.accessibility.AccessibilityNodeInfo?,predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo?{if(node==null)return null;if(predicate(node))return node;for(i in 0 until node.childCount)find(node.getChild(i),predicate)?.let{return it};return null}
        fun awaitNode(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo?{val end=android.os.SystemClock.uptimeMillis()+8000;while(android.os.SystemClock.uptimeMillis()<end){find(instrumentation.uiAutomation.rootInActiveWindow,predicate)?.let{return it};Thread.sleep(100)};return null}
        fun click(node:android.view.accessibility.AccessibilityNodeInfo){var target:android.view.accessibility.AccessibilityNodeInfo?=node;repeat(6){if(target?.isClickable==true){target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return};target=target?.parent};error("文件不可点击")}
        try{
            compose.onNodeWithText("小说").performClick();compose.onNodeWithContentDescription("添加").performClick();compose.onNodeWithText("导入本地小说").performClick()
            var file=awaitNode{it.text?.toString()==filename}
            if(file==null){awaitNode{it.contentDescription?.toString()in listOf("Show roots","显示根目录","显示位置")}?.let{click(it)};awaitNode{it.text?.toString()in listOf("Downloads","下载","下載")}?.let{click(it)};file=awaitNode{it.text?.toString()==filename}}
            assertNotNull("未显示EPUB文件",file);click(file!!)
            compose.waitUntil(15000){compose.onAllNodesWithText("手机文件选择验证").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("第1节").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("这是通过系统文件选择器导入的EPUB正文。",substring=true).fetchSemanticsNodes().isNotEmpty()}
            instrumentation.uiAutomation.takeScreenshot()?.let{b->java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"book-epub-import.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        }finally{resolver.delete(uri,null,null)}
    }
}
