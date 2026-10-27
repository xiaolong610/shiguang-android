package app.shiguang.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing keys stay encrypted at rest; choosing AI is an explicit action for each listening session. */
class AiVoiceSettings(context:Context) {
    private val prefs=context.getSharedPreferences("ai-voice",0)
    private val alias="shiguang-ai-pairing"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    fun save(config:AiVoiceConfig) {
        config.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val token=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(config.token.toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString("url",config.url).putString("token",token).putString("style",config.style).putString("voice",config.voice).putString("revision",config.revision).commit()){tr(R.string.ui_0028)}
    }
    fun load():AiVoiceConfig? = runCatching{
        val parts=(prefs.getString("token",null)?:return null).split(':');require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))}
        val token=String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
        AiVoiceConfig(prefs.getString("url","").orEmpty(),token,prefs.getString("style","natural").orEmpty(),prefs.getString("voice","synthetic-female").orEmpty(),prefs.getString("revision","CosyVoice3-0.5B").orEmpty()).also{it.validate()}
    }.getOrNull()
    fun clear(){prefs.edit().clear().commit();KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(alias))deleteEntry(alias)}}
}
