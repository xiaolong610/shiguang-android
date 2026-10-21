package app.shiguang.mobile

import org.jsoup.parser.Parser
import java.io.Reader

/** Bounded HTML-to-text tokenizer; does not execute scripts or fetch external resources. */
object BookMarkup {
    fun read(input:Reader,sink:BookStorage){
        val text=StringBuilder();val tag=StringBuilder();var inTag=false;var quote='\u0000';var skip=""
        fun flush(){if(text.isNotEmpty()){if(skip.isEmpty())sink.append(Parser.unescapeEntities(text.toString(),false));text.setLength(0)}}
        while(true){val v=input.read();if(v<0)break;val c=v.toChar()
            if(!inTag){if(c=='<'){flush();inTag=true;tag.setLength(0)}else{text.append(c);if(text.length>8192){val amp=text.lastIndexOf("&");if(amp<0||text.length-amp>40)flush()}}}
            else{
                if(c=='>'&&quote=='\u0000'){
                    val raw=tag.toString().trim();val end=raw.startsWith('/');val name=raw.trimStart('/').takeWhile{it.isLetterOrDigit()||it==':'}.lowercase().substringAfter(':')
                    if(skip.isNotEmpty()){if(end&&name==skip)skip=""}
                    else if(!end&&name in setOf("head","script","style","svg","binary"))skip=name
                    else if(name in setOf("p","div","br","section","h1","h2","h3","h4","li","tr","title","subtitle","empty-line"))sink.append("\n")
                    inTag=false;quote='\u0000'
                }else{
                    if(c==quote)quote='\u0000'else if(quote=='\u0000'&&(c=='\''||c=='\"'))quote=c
                    require(tag.length<65536){"网页标签异常过长"};tag.append(c)
                }
            }
        };flush()
    }
}
