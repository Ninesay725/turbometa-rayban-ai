package com.smartview.glassai.services.openclaw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smartview.glassai.utils.APIKeyManager

/**
 * Persistence seam for the gateway settings, the gateway token and the Ed25519 seed. The real
 * store retains existing APIKeyManager storage and isolates new protocol settings/paired tokens
 * in its own encrypted preferences; tests use InMemoryOpenClawSettingsStore.
 */
interface OpenClawSettingsStore {
    var host: String
    var port: Int
    /** "ws" or "wss". */
    var scheme: String
    var compatibility: OpenClawCompatibility
    fun loadToken(): String?
    /** null or blank deletes the stored token (fixes the iOS quirk where emptying the field kept it). */
    fun saveToken(token: String?)
    fun loadDeviceSeed(): ByteArray?
    fun saveDeviceSeed(seed: ByteArray)
    fun loadDeviceToken(endpoint: String, deviceId: String, role: String): String?
    /** null deletes only this endpoint + identity + role's paired credential. */
    fun saveDeviceToken(endpoint: String, deviceId: String, role: String, token: String?)
}

/**
 * [APIKeyManager.getInstance] builds a MasterKey and opens EncryptedSharedPreferences, which is
 * slow and can throw (a restored prefs file without its Keystore key). Resolving it lazily keeps
 * OpenClawNodeService.getInstance() free of I/O at process start; the first Settings read or
 * connect() pays the cost, exactly like every other feature screen today.
 */
class SecureOpenClawSettingsStore(context: Context) : OpenClawSettingsStore {
    private val appContext = context.applicationContext
    private val apiKeyManager: APIKeyManager by lazy { APIKeyManager.getInstance(appContext) }

    // Keep the compatibility sidecar isolated from APIKeyManager and its existing settings.
    private val protocolPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext, "openclaw_protocol",
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override var compatibility: OpenClawCompatibility
        get() = runCatching {
            OpenClawCompatibility.valueOf(protocolPreferences.getString("compatibility", null) ?: "CURRENT")
        }.getOrDefault(OpenClawCompatibility.CURRENT)
        set(value) {
            check(protocolPreferences.edit().putString("compatibility", value.name).commit())
        }

    private fun deviceTokenKey(endpoint: String, deviceId: String, role: String): String =
        "device_token_" + OpenClawDeviceIdentity.sha256Hex("$endpoint\n$deviceId\n$role".toByteArray(Charsets.UTF_8))

    override fun loadDeviceToken(endpoint: String, deviceId: String, role: String): String? =
        protocolPreferences.getString(deviceTokenKey(endpoint, deviceId, role), null)?.takeIf { it.isNotBlank() }

    override fun saveDeviceToken(endpoint: String, deviceId: String, role: String, token: String?) {
        val key = deviceTokenKey(endpoint, deviceId, role)
        val editor = protocolPreferences.edit()
        if (token.isNullOrBlank()) editor.remove(key) else editor.putString(key, token)
        check(editor.commit()) { "Unable to persist OpenClaw device token" }
    }

    override var host: String
        get() = apiKeyManager.getOpenClawHost()
        set(value) = apiKeyManager.saveOpenClawHost(value)

    override var port: Int
        get() = apiKeyManager.getOpenClawPort()
        set(value) = apiKeyManager.saveOpenClawPort(value)

    override var scheme: String
        get() = apiKeyManager.getOpenClawScheme()
        set(value) = apiKeyManager.saveOpenClawScheme(value)

    override fun loadToken(): String? = apiKeyManager.getOpenClawToken()

    override fun saveToken(token: String?) {
        if (token.isNullOrBlank()) apiKeyManager.deleteOpenClawToken() else apiKeyManager.saveOpenClawToken(token)
    }

    override fun loadDeviceSeed(): ByteArray? = apiKeyManager.getOpenClawDeviceSeed()

    override fun saveDeviceSeed(seed: ByteArray) = apiKeyManager.saveOpenClawDeviceSeed(seed)
}
