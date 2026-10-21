package app.shiguang.mobile

import java.io.ByteArrayOutputStream
import java.io.PushbackReader
import java.io.Reader
import java.util.ArrayDeque

object BookRtf {
    private data class State(var skip:Boolean=false,var uc:Int=1)
    fun read(reader:Reader,sink:BookStorage){
        val r=PushbackReader(reader,4);val stack=ArrayDeque<State>();var state=State();var charset=charset("windows-1252");var fallback=0;val bytes=ByteArrayOutputStream()
        fun flush(){if(bytes.size()>0){if(!state.skip)sink.append(String(bytes.toByteArray(),charset));bytes.reset()}}
        while(true){val n=r.read();if(n<0)break;val c=n.toChar()
            when(c){
                '{'->{flush();require(stack.size<512){"RTF嵌套过深"};stack.push(state.copy())}
                '}'->{flush();require(stack.isNotEmpty()){"RTF括号不完整"};state=stack.pop()}
                '\\'->{
                    val v=r.read();if(v<0)break;val next=v.toChar()
                    if(next=='\''){
                        val a=r.read();val b=r.read();require(a>=0&&b>=0){"RTF转义不完整"};val byte="${a.toChar()}${b.toChar()}".toIntOrNull(16)?:error("RTF编码无效")
                        if(fallback>0)fallback-- else bytes.write(byte)
                    }else{
                        flush()
                        if(next=='*'){state.skip=true;continue}
                        if(!next.isLetter()){
                            if(fallback>0)fallback-- else if(!state.skip)when(next){'\\','{','}'->sink.append(next.toString());'~'->sink.append(" ");'_'->sink.append("-")}
                            continue
                        }
                        val word=StringBuilder().append(next);var x=r.read()
                        while(x>=0&&x.toChar().isLetter()){require(word.length<64){"RTF指令异常"};word.append(x.toChar());x=r.read()}
                        val number=StringBuilder();if(x==45){number.append('-');x=r.read()}
                        while(x>=0&&x.toChar().isDigit()){require(number.length<12){"RTF数值异常"};number.append(x.toChar());x=r.read()}
                        if(x>=0&&x!=32)r.unread(x)
                        val value=number.toString().toIntOrNull()
                        when(word.toString()){
                            "fonttbl","colortbl","stylesheet","info","pict","object","header","footer","generator","listtable","listoverridetable"->state.skip=true
                            "ansicpg"->if(value!=null)charset=runCatching{charset(if(value==65001)"UTF-8"else"windows-$value")}.getOrElse{runCatching{charset("GB$value")}.getOrDefault(charset)}
                            "uc"->state.uc=(value?:1).coerceIn(0,16)
                            "u"->if(value!=null&&!state.skip){sink.append((value and 0xffff).toChar().toString());fallback=state.uc}
                            "par","line"->if(!state.skip)sink.append("\n")
                            "tab"->if(!state.skip)sink.append("\t")
                            "emdash"->if(!state.skip)sink.append("—")
                            "endash"->if(!state.skip)sink.append("–")
                            "lquote","rquote"->if(!state.skip)sink.append("’")
                            "ldblquote","rdblquote"->if(!state.skip)sink.append("“")
                            "bin"->{val len=value?:error("RTF二进制段长度缺失");require(len in 0..100*1024*1024);repeat(len){require(r.read()>=0){"RTF二进制段不完整"}}}
                        }
                    }
                }
                '\r','\n'->Unit
                else->{flush();if(fallback>0)fallback-- else if(!state.skip)sink.append(c.toString())}
            }
        };flush();require(stack.isEmpty()){"RTF括号不完整"}
    }
}
