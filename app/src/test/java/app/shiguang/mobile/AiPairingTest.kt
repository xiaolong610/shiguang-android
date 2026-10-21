package app.shiguang.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiPairingTest {
    private fun value()=JSONObject().put("format","shiguang-ai-pairing").put("version",1).put("url","http://192.168.1.20:8765").put("token","a".repeat(32))
    @Test fun generatedPairingFileParsesWithSafeDefaultStyle(){val config=AiPairing.parse("\uFEFF"+value());assertEquals("natural",config.style);assertEquals("http://192.168.1.20:8765",config.url)}
    @Test fun unknownFormatDoesNotBecomeAConfig(){assertTrue(runCatching{AiPairing.parse(value().put("format","other").toString())}.isFailure)}
    @Test fun errorDoesNotEchoTheSecretOrTheInput(){val secret="private-pairing-key-1234567890";val error=runCatching{AiPairing.parse("{broken:$secret")}.exceptionOrNull();assertNotNull(error);assertFalse(error!!.message.orEmpty().contains(secret));assertNull(error.cause)}
    @Test fun publicPlaintextEndpointInImportedFileIsRejected(){assertTrue(runCatching{AiPairing.parse(value().put("url","http://public.example.com").toString())}.isFailure)}
}
