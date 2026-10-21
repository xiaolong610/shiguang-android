package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class NovelImportTest {
    @Test fun utf8GbkAndUtf16AreDecoded(){val s="第一章 春天\n这是正文。";assertEquals(s,NovelImport.decode(s.toByteArray()));assertEquals(s,NovelImport.decode(s.toByteArray(charset("GB18030"))));assertEquals(s,NovelImport.decode(byteArrayOf(0xff.toByte(),0xfe.toByte())+s.toByteArray(Charsets.UTF_16LE)))}
    @Test fun chapterDirectoryPreservesPreambleAndAllBody(){val chapters=NovelImport.chapters("开场的话。\n第一章 春天\n正文甲。\n第二章 秋天\n正文乙。");assertEquals(listOf("序言","第一章 春天","第二章 秋天"),chapters.map{it.title});assertEquals("正文乙。",chapters.last().text)}
    @Test fun longUnsectionedBookIsSplitWithoutBrokenUnicode(){val text="小说😀".repeat(20000);val chapters=NovelImport.chapters(text);assertTrue(chapters.size>1);assertEquals(text,chapters.joinToString(""){it.text});chapters.forEach{assertFalse(Character.isHighSurrogate(it.text.last()))}}
    @Test(expected=IllegalArgumentException::class) fun binaryIsRejected(){NovelImport.decode(byteArrayOf(0,1,0,2))}
}
