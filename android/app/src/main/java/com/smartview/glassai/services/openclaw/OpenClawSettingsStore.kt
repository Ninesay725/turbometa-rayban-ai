package com.smartview.glassai.services.openclaw

import android.content.Context
import com.smartview.glassai.utils.APIKeyManager

/**
 * Persistence seam for the gateway settings, the gateway token and the Ed25519 seed. The real
 * store writes everything into APIKeyManager's EncryptedSharedPreferences (like rtmp_url); tests
 * use InMemoryOpenClawSettingsStore.
 */
interface OpenClawSettingsStore {
    var host: String
    var port: Int
    /** "ws" or "wss". */
    var scheme: String
    fun loadToken(): String?
    /** null or blank deletes the stored token (fixes the iOS quirk where emptying the field kept it). */
    fun saveToken(token: String?)
    fun loadDeviceSeed(): ByteArray?
    fun saveDeviceSeed(seed: ByteArray)
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
