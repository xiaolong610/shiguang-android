package app.shiguang.mobile

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class ShiguangApplication:Application(),ImageLoaderFactory {
    override fun onCreate(){super.onCreate();AppLanguage.initialize(this)}
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);AppLanguage.refresh()}
    override fun newImageLoader()=AppImages.get(this)
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)AppImages.trim()}
    override fun onLowMemory(){super.onLowMemory();AppImages.trim()}
}
object AppImages {
    @Volatile private var loader:ImageLoader?=null
    @Synchronized fun get(context:android.content.Context):ImageLoader = loader?:ImageLoader.Builder(context.applicationContext)
        .components{add(SvgDecoder.Factory())}
        .okHttpClient(ContentNetwork.foreground.newBuilder().dispatcher(okhttp3.Dispatcher().apply{maxRequests=4;maxRequestsPerHost=2}).build())
        .memoryCache{MemoryCache.Builder(context.applicationContext).maxSizePercent(.10).build()}
        .diskCache{DiskCache.Builder().directory(File(context.applicationContext.cacheDir,"images")).maxSizeBytes(192L*1024*1024).build()}
        .crossfade(false).build().also{loader=it}
    fun trim(){loader?.memoryCache?.clear()}
}
