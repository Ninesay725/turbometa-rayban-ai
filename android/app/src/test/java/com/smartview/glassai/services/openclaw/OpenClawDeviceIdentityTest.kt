package com.smartview.glassai.services.openclaw

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawDeviceIdentityTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 8032 §7.1 TEST 1: seed, public key, signature over the empty message. */
    private val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val rfcPublicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val rfcSignatureOfEmpty = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )

    @Test
    fun rfc8032TestVectorOneMatches() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        assertArrayEquals(rfcPublicKey, identity.publicKey)
        val signature = Base64.getUrlDecoder().decode(identity.sign(""))
        assertArrayEquals(rfcSignatureOfEmpty, signature)
    }

    @Test
    fun deviceIdIsSha256HexOfRawPublicKey() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val expected = MessageDigest.getInstance("SHA-256").digest(rfcPublicKey)
            .joinToString("") { "%02x".format(it) }
        assertEquals(64, identity.deviceId.length)
        assertEquals(expected, identity.deviceId)
    }

    @Test
    fun publicKeyIsBase64UrlWithoutPadding() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val encoded = identity.publicKeyBase64Url
        assertTrue(encoded, !encoded.contains('=') && !encoded.contains('+') && !encoded.contains('/'))
        assertArrayEquals(rfcPublicKey, Base64.getUrlDecoder().decode(encoded))
    }

    @Test
    fun signatureStringMatchesTheIosLayout() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            deviceId = identity.deviceId,
            clientId = OpenClawProtocol.CLIENT_ID,
            clientMode = OpenClawProtocol.CLIENT_MODE,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMs = 1711700000000L,
            token = "abc",
            nonce = "n1",
            platform = OpenClawProtocol.PLATFORM,
            deviceFamily = null,
        )
        assertEquals(
            "v3|${identity.deviceId}|openclaw-android|node|operator|operator.read,operator.write|1711700000000|abc|n1|android|",
            payload,
        )
    }

    @Test
    fun missingTokenIsAnEmptyField() {
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            deviceId = "d", clientId = "c", clientMode = "node", role = "operator",
            scopes = listOf("a", "b"), signedAtMs = 1L, token = null, nonce = "n",
            platform = "android", deviceFamily = "Pixel 5",
        )
        assertEquals("v3|d|c|node|operator|a,b|1||n|android|pixel5", payload)
    }

    @Test
    fun normalizeForAuthTrimsLowercasesAndStripsPunctuation() {
        assertEquals("android", OpenClawDeviceIdentity.normalizeForAuth("  Android "))
        assertEquals("ray-ban_meta.v2", OpenClawDeviceIdentity.normalizeForAuth("Ray-Ban_Meta.v2!"))
        assertEquals("", OpenClawDeviceIdentity.normalizeForAuth(null))
        assertEquals("", OpenClawDeviceIdentity.normalizeForAuth("   "))
    }

    @Test
    fun connectSignatureVerifiesWithTink() {
        val identity = OpenClawDeviceIdentity.fromSeed(rfcSeed)
        val signedAt = 1711700000000L
        val signature = identity.signConnect(
            clientId = OpenClawProtocol.CLIENT_ID,
            clientMode = OpenClawProtocol.CLIENT_MODE,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMs = signedAt,
            token = "tok",
            nonce = "nonce-1",
            platform = OpenClawProtocol.PLATFORM,
            deviceFamily = null,
        )
        val payload = OpenClawDeviceIdentity.buildSignaturePayload(
            identity.deviceId, OpenClawProtocol.CLIENT_ID, OpenClawProtocol.CLIENT_MODE, OpenClawProtocol.ROLE,
            OpenClawProtocol.SCOPES, signedAt, "tok", "nonce-1", OpenClawProtocol.PLATFORM, null,
        )
        // verify() throws GeneralSecurityException on a bad signature
        Ed25519Verify(identity.publicKey).verify(
            Base64.getUrlDecoder().decode(signature),
            payload.toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun storeCreatesOnceThenReloadsTheSameIdentity() {
        val store = InMemoryOpenClawSettingsStore()
        val first = OpenClawDeviceIdentityStore.loadOrCreate(store)
        val second = OpenClawDeviceIdentityStore.loadOrCreate(store)
        assertEquals(1, store.seedSaves)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(32, store.loadDeviceSeed()!!.size)
    }

    @Test
    fun storeReplacesAnInvalidSeed() {
        val store = InMemoryOpenClawSettingsStore()
        store.saveDeviceSeed(ByteArray(5))
        val identity = OpenClawDeviceIdentityStore.loadOrCreate(store)
        assertEquals(2, store.seedSaves)
        assertEquals(32, store.loadDeviceSeed()!!.size)
        // the persisted seed reproduces the identity that was handed out
        assertEquals(identity.deviceId, OpenClawDeviceIdentity.fromSeed(store.loadDeviceSeed()!!).deviceId)
    }
}
