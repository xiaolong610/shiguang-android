package app.shiguang.mobile

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNovelDeviceTest {
    @Test fun largeDirectorySurvivesActivityStateThroughSmallCacheKey(){val context=InstrumentationRegistry.getInstrumentation().targetContext;val entry=Demo.entries.last();val detail=Detail(entry,listOf(Line("目录",(0 until 3000).map{Chapter("第${it+1}章 "+"长篇小说标题".repeat(10),"https://example.com/chapter/$it")})));val cache=ReaderNavigationCache(context);val key=cache.save(Triple(detail,0,2222));assertEquals(24,key.length);val restored=cache.restore(key)!!;assertEquals(3000,restored.first.lines.single().chapters.size);assertEquals(2222,restored.third);assertNull(cache.restore("../bad"))}
    @Test fun documentImportIsReadableWithoutNetworkAndDuplicateImportKeepsIdentity()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"拾光导入测试.txt");put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")})?:error("无法创建测试文档")
        try{
            resolver.openOutputStream(uri)!!.use{it.write("第一章 清晨\n这是手机本地导入的第一章。\n第二章 傍晚\n这是手机本地导入的第二章。".toByteArray(charset("GB18030")))}
            val first=LocalNovels(context).import(uri);val second=LocalNovels(context).import(uri)
            assertEquals(first.id,second.id);assertEquals(1,LocalNovels(context).entries().count{it.id==first.id})
            val repo=ContentRepository(context);val detail=repo.detail(LocalNovels.source,first)
            assertEquals(2,detail.lines.single().chapters.size);assertEquals("这是手机本地导入的第二章。",repo.page(LocalNovels.source,first,detail.lines.single().chapters[1]).text)
            context.getSharedPreferences("novel-reading",0).let{NovelPreferences(context).position(first.key,1,3,17)}
            assertEquals(3 to 17,NovelPreferences(context).position(first.key,1))
        }finally{resolver.delete(uri,null,null)}
    }
}
