package app.shiguang.mobile

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AiVoiceClientTest {
    @Test fun privateEndpointRequiresPairingAndParsesHealth()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setBody("{\"ready\":true,\"model\":\"CosyVoice3\"}"));val client=AiVoiceClient(AiVoiceConfig(server.url("/").toString(),"a".repeat(32)),Files.createTempDirectory("voice").toFile());assertEquals("CosyVoice3",client.health().getString("model"));assertEquals("Bearer "+"a".repeat(32),server.takeRequest().getHeader("Authorization"))}
    }
    @Test fun authenticationFailureDoesNotBecomeAnAudioFile()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setResponseCode(401));val client=AiVoiceClient(AiVoiceConfig(server.url("/").toString(),"a".repeat(32)),Files.createTempDirectory("voice").toFile());val failure=runCatching{client.synthesize("测试小说")}.exceptionOrNull();assertTrue(failure?.message.orEmpty().contains("配对密钥"))}
    }
    @Test fun unexpectedJsonResponseIsRejected()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setBody("{\"message\":\"not audio\"}"));val client=AiVoiceClient(AiVoiceConfig(server.url("/").toString(),"a".repeat(32)),Files.createTempDirectory("voice").toFile());assertTrue(runCatching{client.synthesize("测试小说")}.exceptionOrNull()?.message.orEmpty().contains("WAV"))}
    }
    @Test fun publicHttpIsRejected(){assertTrue(runCatching{AiVoiceConfig("http://example.com","a".repeat(32)).validate()}.isFailure)}
    @Test fun hostnameThatOnlyLooksPrivateIsRejected(){assertTrue(runCatching{AiVoiceConfig("http://192.168.evil.example","a".repeat(32)).validate()}.isFailure)}
    @Test fun generatedAudioIsCachedAndDoesNotSendTextTwice()=runBlocking {
        MockWebServer().use{server->val wav=ByteArray(48);"RIFF".toByteArray().copyInto(wav,0);"WAVE".toByteArray().copyInto(wav,8);server.enqueue(MockResponse().setBody(Buffer().write(wav)));val client=AiVoiceClient(AiVoiceConfig(server.url("/").toString(),"a".repeat(32)),Files.createTempDirectory("voice").toFile());val first=client.synthesize("测试小说");assertTrue(first.isFile);assertEquals(first,client.synthesize("测试小说"));assertEquals(1,server.requestCount);val sent=server.takeRequest().body.readUtf8();assertTrue(sent.contains("测试小说"))}
    }
    @Test fun cancellingAWaitingSynthesisClosesTheRequest()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val client=AiVoiceClient(AiVoiceConfig(server.url("/").toString(),"a".repeat(32)),Files.createTempDirectory("voice").toFile());val job=async(Dispatchers.Default){client.synthesize("等待中的小说段落")};assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS));withTimeout(3000){job.cancelAndJoin()};assertTrue(job.isCancelled)}
    }
}
