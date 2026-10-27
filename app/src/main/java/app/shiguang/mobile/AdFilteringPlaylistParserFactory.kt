package app.shiguang.mobile

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdFilteringPlaylistParserFactory(
    private val enabled:()->Boolean,private val knownAdSource:Boolean,
    private val report:(Uri,AdFilterResult)->Unit,
):HlsPlaylistParserFactory {
    private val delegate=DefaultHlsPlaylistParserFactory()
    override fun createPlaylistParser():ParsingLoadable.Parser<HlsPlaylist> = wrap(delegate.createPlaylistParser())
    override fun createPlaylistParser(multivariantPlaylist:HlsMultivariantPlaylist,previousMediaPlaylist:HlsMediaPlaylist?):ParsingLoadable.Parser<HlsPlaylist> =
        // Alternate audio/subtitles need aligned edits across renditions; leave those streams intact.
        wrap(delegate.createPlaylistParser(multivariantPlaylist,previousMediaPlaylist),multivariantPlaylist.audios.isNotEmpty()||multivariantPlaylist.subtitles.isNotEmpty())
    private fun wrap(parser:ParsingLoadable.Parser<HlsPlaylist>,alternateRenditions:Boolean=false)=ParsingLoadable.Parser<HlsPlaylist> {uri,input->
        val raw=input.readBytesLimited(6*1024*1024)
        val text=String(raw,Charsets.UTF_8)
        val result=when {
            !enabled()->AdFilterResult(text,text.contains("#EXTINF:"),reason=tr(R.string.ui_0000))
            alternateRenditions->AdFilterResult(text,text.contains("#EXTINF:"),reason=tr(R.string.ui_0001))
            else->HlsAdFilter.clean(text,uri.toString(),knownAdSource)
        }
        // Validate the edited text using Media3 itself; if parsing fails, use the original bytes.
        val parsed=try {parser.parse(uri,ByteArrayInputStream(result.text.toByteArray(Charsets.UTF_8)))}catch(e:Exception){
            if(result.removed.isEmpty())throw e
            val original=parser.parse(uri,ByteArrayInputStream(raw))
            report(uri,AdFilterResult(text,true,reason=tr(R.string.ui_0002)))
            return@Parser original
        }
        if(result.mediaPlaylist)report(uri,result)
        parsed
    }
}
