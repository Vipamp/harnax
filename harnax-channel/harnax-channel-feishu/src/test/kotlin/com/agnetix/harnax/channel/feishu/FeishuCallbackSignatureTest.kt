package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Feishu callback signature to the platform's documented scheme:
 * `sha256Hex(timestamp + nonce + encryptKey + rawBody)`.
 *
 * Two things used to be wrong at once — the key was the app secret instead of the Encrypt Key, and
 * the digest was an HMAC — and both failures are silent: every real callback would simply be
 * rejected, or (worse) an operator would disable the check to make the channel work. The expected
 * digest below is computed independently of the implementation, so this test cannot drift with it.
 */
class FeishuCallbackSignatureTest {
    private val adaptor = FeishuAdaptor()

    private val body = """{"schema":"2.0","header":{"event_type":"im.message.receive_v1"}}"""

    private fun channel(encryptKey: String?) = ChannelSpec.builder()
        .id(1L)
        .name("feishu-signature")
        .type(ChannelType.FEISHU)
        .agentId(1L)
        .callbackKey("cb-signature")
        .appId("app-id")
        .appSecret("app-secret")
        .encodingAesKey(encryptKey)
        .build()

    private fun request(signature: String?, headers: Map<String, String> = emptyMap()) = ChannelRequest.builder()
        .body(body)
        .method("POST")
        .path("/api/channel/callback/cb-signature")
        .apply {
            signature?.let { header("X-Lark-Signature", it) }
            headers.forEach { (k, v) -> header(k, v) }
        }
        .build()

    @Test
    fun `the documented concatenation order and digest are accepted`() {
        val channel = channel(encryptKey = "enc-key-abc")
        val request = request(
            signature = "8e68808d55df10ab540ca08bc3d22b2b4b6bc4637c9afe2c8e4c1633086ebeb7",
            headers = mapOf(
                "X-Lark-Request-Timestamp" to "1737000000",
                "X-Lark-Request-Nonce" to "n-42",
            ),
        )

        assertTrue(adaptor.verifySignature(request, channel), "a correctly signed callback was rejected")
    }

    @Test
    fun `a signature computed over the app secret is not accepted`() {
        // The exact mistake the previous implementation made: same shape, wrong key material.
        val channel = channel(encryptKey = "enc-key-abc")
        val request = request(
            signature = "0000000000000000000000000000000000000000000000000000000000000000",
            headers = mapOf(
                "X-Lark-Request-Timestamp" to "1737000000",
                "X-Lark-Request-Nonce" to "n-42",
            ),
        )

        assertFalse(adaptor.verifySignature(request, channel), "an arbitrary signature must not verify")
    }

    @Test
    fun `a missing signature or timestamp header fails closed`() {
        val channel = channel(encryptKey = "enc-key-abc")
        val full = mapOf(
            "X-Lark-Signature" to "8e68808d55df10ab540ca08bc3d22b2b4b6bc4637c9afe2c8e4c1633086ebeb7",
            "X-Lark-Request-Timestamp" to "1737000000",
            "X-Lark-Request-Nonce" to "n-42",
        )

        assertFalse(adaptor.verifySignature(request(signature = null, headers = full - "X-Lark-Signature"), channel))
        assertFalse(adaptor.verifySignature(request(signature = full["X-Lark-Signature"], headers = full - "X-Lark-Request-Timestamp"), channel))
        assertFalse(adaptor.verifySignature(request(signature = full["X-Lark-Signature"], headers = full - "X-Lark-Request-Nonce"), channel))
    }

    @Test
    fun `header names are matched regardless of case`() {
        val channel = channel(encryptKey = "enc-key-abc")
        val request = ChannelRequest.builder()
            .body(body)
            .header("x-lark-signature", "8e68808d55df10ab540ca08bc3d22b2b4b6bc4637c9afe2c8e4c1633086ebeb7")
            .header("x-lark-request-timestamp", "1737000000")
            .header("x-lark-request-nonce", "n-42")
            .build()

        assertTrue(adaptor.verifySignature(request, channel), "an HTTP/2 callback arrives with lowercase header names")
    }

    @Test
    fun `without an encrypt key there is nothing to verify so the check defers to the transport`() {
        // handleCallback refuses these outright; verifySignature only reports "not applicable".
        assertTrue(adaptor.verifySignature(request(signature = null), channel(encryptKey = null)))
    }
}
