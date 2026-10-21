package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class NovelTextTest {
    @Test fun longChapterChunksRetainEveryNonWhitespaceCharacterAndExactOffsets(){
        val text=("这是小说中的一句话。下一句有表情😀，不能拆坏字符！\n\n".repeat(500))+"最后一段。"
        val pieces=NovelText.pieces(text)
        assertTrue(pieces.size>10)
        pieces.forEach{p->assertTrue(p.text.length<=280);assertEquals(text.substring(p.start,p.end),p.text);assertFalse(Character.isLowSurrogate(p.text.first()));assertFalse(Character.isHighSurrogate(p.text.last()))}
        assertEquals(text.filterNot{it.isWhitespace()},pieces.joinToString(""){it.text}.filterNot{it.isWhitespace()})
    }
    @Test fun aLongSentenceWithNoPunctuationIsBounded(){assertTrue(NovelText.pieces("字".repeat(9000)).all{it.text.length<=280})}
    @Test fun paragraphsAndOffsetsAgreeForRepeatedAndBlankLines(){val text="相同段落。\n\n相同段落。\n最后。";assertEquals(3,NovelText.paragraphs(text).size);assertEquals(1,NovelText.paragraphAt(text,NovelText.paragraphOffset(text,1)));assertEquals(2,NovelText.paragraphAt(text,text.length))}
    @Test fun whitespaceOnlyDoesNotScheduleSpeech(){assertTrue(NovelText.pieces(" \n\t").isEmpty())}
}
