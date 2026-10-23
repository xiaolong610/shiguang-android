package app.shiguang.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** A book can be below the viewport after local imports; scroll the real grid to it. */
fun ComposeTestRule.openDemoNovel(){
    // Each interaction test starts at the book's first page; restoration is tested separately.
    NovelPreferences(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).position(Demo.entries.last().key,0,0,0)
    onNodeWithContentDescription("切换小说分类").performClick()
    waitUntil(20000){onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()}
    onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("entry:${Demo.entries.last().key}"))
    onNodeWithTag("entry:${Demo.entries.last().key}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
    try{waitUntil(20000){onAllNodesWithText("第一章 · 蓝色信箱").fetchSemanticsNodes().isNotEmpty()}}
    finally{
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val dir=ins.targetContext.getExternalFilesDir(null)
        java.io.File(dir,"reliability010-novel-navigation.txt").writeText(onRoot().printToString())
        ins.uiAutomation.takeScreenshot()?.let{b->java.io.File(dir,"reliability010-novel-navigation.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
}
