package com.smartview.glassai.services.openclaw

class InMemoryOpenClawSettingsStore : OpenClawSettingsStore {
    override var host: String = OpenClawProtocol.DEFAULT_HOST
    override var port: Int = OpenClawProtocol.DEFAULT_PORT
    override var scheme: String = OpenClawProtocol.SCHEME_WS
    private var token: String? = null
    private var seed: ByteArray? = null
    var seedSaves = 0

    override fun loadToken(): String? = token
    override fun saveToken(token: String?) {
        this.token = token?.takeIf { it.isNotBlank() }
    }

    override fun loadDeviceSeed(): ByteArray? = seed
    override fun saveDeviceSeed(seed: ByteArray) {
        seedSaves++
        this.seed = seed.copyOf()
    }
}
