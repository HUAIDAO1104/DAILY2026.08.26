package com.pengxh.daily.app.ai

import android.content.Context
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues

data class AiServiceConfig(val baseUrl: String, val model: String, val apiKey: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

class AiConfigStore(context: Context) {
    private val secureStore = SecureValueStore(context)

    fun load() = AiServiceConfig(
        baseUrl = FIXED_BASE_URL,
        model = SaveKeyValues.loadString(Constant.AI_MODEL_KEY, ""),
        apiKey = secureStore.loadApiKey()
    )

    fun save(model: String, apiKey: String?) {
        SaveKeyValues.saveString(Constant.AI_MODEL_KEY, model.trim())
        if (apiKey != null) secureStore.saveApiKey(apiKey.trim())
    }

    companion object {
        const val FIXED_BASE_URL = "https://llm-api.mcisaas.com/v1"
    }
}
