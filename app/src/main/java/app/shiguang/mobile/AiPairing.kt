package app.shiguang.mobile

import org.json.JSONObject

object AiPairing {
    fun parse(text:String):AiVoiceConfig {
        require(text.toByteArray(Charsets.UTF_8).size<=32*1024){"配对文件过大"}
        return try{
            val value=JSONObject(text.trim().removePrefix("\uFEFF"))
            require(value.optString("format")=="shiguang-ai-pairing"&&value.optInt("version")==1)
            AiVoiceConfig(value.getString("url"),value.getString("token"),value.optString("style","natural")).also{it.validate()}
        }catch(e:Exception){throw IllegalArgumentException("配对文件格式不正确，请使用电脑语音助手生成的文件")}
    }
}
