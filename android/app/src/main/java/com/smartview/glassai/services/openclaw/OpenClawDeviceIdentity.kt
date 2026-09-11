package com.smartview.glassai.services.openclaw

import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.util.Base64

/**
 * Per-install Ed25519 identity (research §2.4). The 32-byte seed is the only persisted secret;
 * the public key, deviceId and every signature derive from it.
 *
 * Signature payload (v3), UTF-8, signed with Ed25519, output base64url without padding:
 * `v3|deviceId|clientId|clientMode|role|scopes(',')|signedAtMs|token or ''|nonce|normalize(platform)|normalize(deviceFamily) or ''`
 */
class OpenClawDeviceIdentity private constructor(
    private val seed: ByteArray,
    val publicKey: ByteArray,
) {
    private val signer = Ed25519Sign(seed)

    /** Lowercase hex SHA-256 of the raw 32-byte public key (64 chars). */
    val deviceId: String = sha256Hex(publicKey)

    /** base64url(raw public key), no padding. */
    val publicKeyBase64Url: String = base64Url(publicKey)

    /** Signs an arbitrary UTF-8 payload; returns base64url without padding, "" on failure. */
    fun sign(payload: String): String = try {
        base64Url(signer.sign(payload.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "sign failed: ${e.message}")
        ""
    }

    fun signConnect(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String,
        platform: String,
        deviceFamily: String?,
    ): String = sign(
        buildSignaturePayload(
            deviceId, clientId, clientMode, role, scopes, signedAtMs, token, nonce, platform, deviceFamily,
        )
    )

    /** Copy of the seed for persistence (module-internal); never log it. */
    internal fun seedCopy(): ByteArray = seed.copyOf()

    companion object {
        private const val TAG = "OpenClawDeviceIdentity"
        const val SEED_LENGTH = 32

        fun generate(): OpenClawDeviceIdentity {
            val pair = Ed25519Sign.KeyPair.newKeyPair()
            return OpenClawDeviceIdentity(pair.privateKey, pair.publicKey)
        }

        fun fromSeed(seed: ByteArray): OpenClawDeviceIdentity {
            require(seed.size == SEED_LENGTH) { "Ed25519 seed must be $SEED_LENGTH bytes, got ${seed.size}" }
            val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            return OpenClawDeviceIdentity(seed.copyOf(), pair.publicKey)
        }

        fun buildSignaturePayload(
            deviceId: String,
            clientId: String,
            clientMode: String,
            role: String,
            scopes: List<String>,
            signedAtMs: Long,
            token: String?,
            nonce: String,
            platform: String,
            deviceFamily: String?,
        ): String = listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token ?: "",
            nonce,
            normalizeForAuth(platform),
            normalizeForAuth(deviceFamily),
        ).joinToString("|")

        /** JS String.trim + ASCII lowercase, exactly as gateway-client/src/device-auth.ts. */
        fun normalizeForAuth(value: String?): String {
            if (value == null) return ""
            val whitespace = "\u0009\u000A\u000B\u000C\u000D\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
            return value.trim { it in whitespace }.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
        }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Loads the persisted seed or generates + stores a new one (stable across launches). */
object OpenClawDeviceIdentityStore {
    private const val TAG = "OpenClawIdentityStore"

    fun loadOrCreate(store: OpenClawSettingsStore): OpenClawDeviceIdentity {
        val seed = store.loadDeviceSeed()
        if (seed != null && seed.size == OpenClawDeviceIdentity.SEED_LENGTH) {
            try {
                return OpenClawDeviceIdentity.fromSeed(seed)
            } catch (e: Exception) {
                Log.w(TAG, "stored seed unusable (${e.message}); generating a new identity")
            }
        }
        val fresh = OpenClawDeviceIdentity.generate()
        store.saveDeviceSeed(fresh.seedCopy())
        return fresh
    }
}
