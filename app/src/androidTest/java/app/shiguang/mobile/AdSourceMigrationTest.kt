package app.shiguang.mobile

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdSourceMigrationTest {
    @Test fun upgradeDisablesPreviouslyImportedFlaggedSourceWithoutRemovingFavorites(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val source=SourceCodec.parse("https://bfzyapi.com/api.php/provide/vod").single().copy(enabled=true,rules="{}")
        LibraryStore(context).use{store->store.putSource(source);store.favorite(Demo.entries.last(),true)}
        context.getSharedPreferences("source-options",0).edit().remove("ad-source-review-v1").apply()
        val owners=ViewModelStore()
        instrumentation.runOnMainSync {
            val model=ViewModelProvider(owners,ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppModel::class.java]
            assertFalse(model.source(source.id)!!.enabled)
            assertTrue(model.favorites.any{it.key==Demo.entries.last().key})
            owners.clear()
        }
        LibraryStore(context).use{store->assertFalse(store.sources().first{it.id==source.id}.enabled);store.deleteSource(source.id)}
    }
}
