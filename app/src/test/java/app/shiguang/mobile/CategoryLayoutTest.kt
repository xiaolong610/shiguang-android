package app.shiguang.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CategoryLayoutTest {
    @Test fun existingAnimeRulesRemainVideoAndKeepTheirId(){
        val raw="""{"engine":"maccms","kind":"video","url":"https://example.com/api","rules":{"type":"anime"}}"""
        val source=SourceCodec.parse(raw).single();assertEquals(Kind.VIDEO,source.kind);assertEquals(ContentCategory.ANIME,source.category)
        val restored=SourceCodec.parse(source.json().toString()).single();assertEquals(source.id,restored.id);assertEquals(source.category,restored.category)
    }
    @Test fun allFourCategoriesRoundTripInSavedEntries(){
        ContentCategory.entries.forEach{c->val e=Entry("item","source","标题",c.kind,"https://example.com/1",category=c);assertEquals(c,Entry.parse(e.json()).category)}
    }
    @Test fun oldEntriesWithoutCategoryCanStillBeRead(){val raw=Demo.entries.last().json().apply{remove("category")};assertEquals(ContentCategory.NOVEL,Entry.parse(raw).category)}
    @Test fun untrustedCategoryCannotTurnANovelIntoAVideo(){val raw=Demo.entries.last().json().put("category","anime");assertEquals(ContentCategory.NOVEL,Entry.parse(raw).category)}
    @Test fun animationKindAliasKeepsCompatibleStableIdentity(){val old=SourceCodec.parse("""{"kind":"video","api":"https://example.com/api"}""").single();val anime=SourceCodec.parse("""{"kind":"anime","api":"https://example.com/api"}""").single();assertEquals(old.id,anime.id);assertEquals(ContentCategory.ANIME,anime.category)}
    @Test fun explicitVideoCategoryOverridesAnimeEngineHint(){val source=Source("id","源",Kind.VIDEO,"kazumi",rules="""{"category":"video","type":"anime"}""");assertEquals(ContentCategory.VIDEO,source.category)}
    @Test fun descendingOrderKeepsOriginalEpisodeIndicesRatherThanSortingTitles(){assertEquals(listOf(11,10,9,8,7,6,5,4,3,2,1,0),episodeOrder(12,true));assertEquals((0..11).toList(),episodeOrder(12,false));assertTrue(episodeOrder(0,true).isEmpty())}
}
