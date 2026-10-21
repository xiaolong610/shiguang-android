package app.shiguang.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HlsAdFilterTest {
    private val base="https://cdn.example/movie/index.m3u8"
    private fun playlist(vararg parts:Pair<Double,String>,end:Boolean=true)=buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        for((seconds,uri)in parts)append("#EXTINF:$seconds,\n$uri\n")
        if(end)append("#EXT-X-ENDLIST\n")
    }
    private fun cueFilm()=playlist(80.0 to "main1.ts",10.0 to "ads/ad.ts",80.0 to "main2.ts")
        .replace("#EXTINF:10.0,","#EXT-X-CUE-OUT:10\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,")
        .replace("#EXTINF:80.0,\nmain2.ts","#EXT-X-CUE-IN\n#EXT-X-DISCONTINUITY\n#EXTINF:80.0,\nmain2.ts")
    @Test fun removesPairedAdWithoutFetchingOrChangingMainUris(){
        val result=HlsAdFilter.clean(cueFilm(),base)
        assertEquals(1,result.removed.size);assertEquals(1,result.removedSegments)
        assertEquals(10.0,result.removedSeconds,0.001)
        assertFalse(result.text.contains("ads/ad.ts"));assertTrue(result.text.contains("main1.ts"));assertTrue(result.text.contains("main2.ts"))
        assertTrue(result.text.contains("#EXT-X-DISCONTINUITY\n#EXTINF:80.0,\nmain2.ts"))
    }
    @Test fun insertsDiscontinuityAtSpliceWhenCueDoesNotProvideOne(){
        val input=cueFilm().replace("#EXT-X-DISCONTINUITY\n","")
        val result=HlsAdFilter.clean(input,base)
        assertEquals(1,result.removed.size)
        assertTrue(result.text.contains("#EXT-X-DISCONTINUITY\n#EXTINF:80.0,\nmain2.ts"))
    }
    @Test fun normalCutOrShortChapterIsNotAnAd(){
        val input=playlist(120.0 to "part1.ts",20.0 to "part2.ts",120.0 to "part3.ts").replace("#EXTINF:20.0,","#EXT-X-DISCONTINUITY\n#EXTINF:20.0,").replace("#EXTINF:120.0,\npart3","#EXT-X-DISCONTINUITY\n#EXTINF:120.0,\npart3")
        assertEquals(input,HlsAdFilter.clean(input,base,true).text)
    }
    @Test fun sameDirectoryWithManyDiscontinuitiesIsNotAllAds(){
        val input=buildString {append("#EXTM3U\n#EXT-X-TARGETDURATION:5\n");repeat(47){i->append("#EXT-X-DISCONTINUITY\n");repeat(5){j->append("#EXTINF:4.0,\n${i}_${j}.ts?hash=opaque\n")}};append("#EXT-X-ENDLIST\n")}
        val result=HlsAdFilter.clean(input,base,true)
        assertEquals(input,result.text);assertEquals(47,result.discontinuities);assertEquals(0,result.removedSegments)
    }
    @Test fun boundedExplicitAdPathCanIdentifyPreroll(){
        val input=playlist(10.0 to "ads/ad.ts",100.0 to "main.ts").replace("#EXTINF:100.0,","#EXT-X-DISCONTINUITY\n#EXTINF:100.0,")
        val result=HlsAdFilter.clean(input,base)
        assertEquals(1,result.removedSegments);assertFalse(result.text.contains("ads/ad.ts"));assertTrue(result.text.contains("#EXT-X-MEDIA-SEQUENCE:1"))
    }
    @Test fun knownSourceRequiresSandwichedLongMainAndDifferentFamily(){
        val input=playlist(150.0 to "content/a.ts",15.0 to "different/b.ts",150.0 to "content/c.ts").replace("#EXTINF:15.0,","#EXT-X-DISCONTINUITY\n#EXTINF:15.0,").replace("#EXTINF:150.0,\ncontent/c","#EXT-X-DISCONTINUITY\n#EXTINF:150.0,\ncontent/c")
        assertEquals(0,HlsAdFilter.clean(input,base,false).removedSegments)
        assertEquals(1,HlsAdFilter.clean(input,base,true).removedSegments)
    }
    @Test fun encryptedOrRangeOrLiveOrMapPlaylistsRemainUntouched(){
        val input=cueFilm()
        val variants=listOf(input.replace("#EXT-X-VERSION:3","#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""),
            input.replace("#EXTINF:10.0,","#EXTINF:10.0,\n#EXT-X-BYTERANGE:1024@0"),
            input.replace("#EXT-X-ENDLIST\n",""),input.replace("#EXT-X-VERSION:3","#EXT-X-VERSION:3\n#EXT-X-MAP:URI=\"init.mp4\""))
        variants.forEach{assertEquals(it,HlsAdFilter.clean(it,base).text)}
    }
    @Test fun tooMuchOrUnbalancedAdSignalDoesNotDeleteFilm(){
        val excessive=cueFilm().replace("#EXTINF:80.0,","#EXTINF:10.0,")
        assertEquals(excessive,HlsAdFilter.clean(excessive,base).text)
        val unbalanced=cueFilm().replace("#EXT-X-CUE-IN\n","")
        assertEquals(unbalanced,HlsAdFilter.clean(unbalanced,base).text)
        val contradictory=cueFilm().replace("#EXT-X-CUE-OUT:10","#EXT-X-CUE-OUT:60")
        assertEquals(contradictory,HlsAdFilter.clean(contradictory,base).text)
    }
    @Test fun masterPlaylistAndInterstitialAssetAreNotDeleted(){
        val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nlow/index.m3u8\n"
        assertEquals(master,HlsAdFilter.clean(master,base).text)
        val film=playlist(100.0 to "film.ts").replace("#EXTINF:100.0,","#EXT-X-DATERANGE:ID=\"ad\",CLASS=\"com.apple.hls.interstitial\",X-ASSET-URI=\"ad.mp4\"\n#EXTINF:100.0,")
        assertEquals(film,HlsAdFilter.clean(film,base).text)
    }
    @Test fun originalAndFilteredProgressMapAcrossAd(){
        val r=HlsAdFilter.clean(cueFilm(),base)
        assertEquals(75000L,r.originalToFilteredMs(75000));assertEquals(80000L,r.originalToFilteredMs(85000))
        assertEquals(90000L,r.originalToFilteredMs(100000));assertEquals(100000L,r.filteredToOriginalMs(90000))
        assertEquals(90000L,r.filteredToOriginalMs(80000))
    }
    @Test fun legacyAdSourcesDefaultDisabledAndKeepWarningAcrossPersistence(){
        val imported=SourceCodec.parse("""[{"name":"带广告","api":"https://example.com/api","adFilterEnabled":true,"tip":"贴片广告"}]""").single()
        assertFalse(imported.enabled);assertTrue(JSONObject(imported.rules).optBoolean("knownAds"))
        assertFalse(SourceCodec.parse(imported.json().toString()).single().enabled)
        assertTrue(SourceCodec.parse(imported.copy(enabled=true).json().toString()).single().enabled)
        assertFalse(SourceCodec.parse("https://bfzyapi.com/api.php/provide/vod").single().enabled)
        val normal=SourceCodec.parse("""[{"name":"切片","group":"切片","api":"https://example.com/normal"}]""").single()
        assertTrue(normal.enabled)
    }
}
