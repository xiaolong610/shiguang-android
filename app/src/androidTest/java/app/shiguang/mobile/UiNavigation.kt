package app.shiguang.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** A book can be below the viewport after local imports; scroll the real grid to it. */
fun ComposeTestRule.openDemoNovel(){
    // Each interaction test starts at the book's first page; restoration is tested separately.
    NovelPreferences(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).position(Demo.entries.last().key,0,0,0)
    onNodeWithContentDescription("切换小说分类").performClick()
    waitUntil(20000){onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()}
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText("把今天寄给明天"))
    onAllNodesWithText("把今天寄给明天").onLast().performClick()
}
