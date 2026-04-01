package org.simpmusic.aiservice

import com.maxrave.domain.data.model.metadata.Lyrics

class AiClient {
    private var aiService: AiService? = null
    var host = AIHost.GEMINI
        set(value) {
            field = value
            rebuildAiService()
        }
    var apiKey: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customModelId: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customBaseUrl: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customHeaders: Map<String, String>? = null
        set(value) {
            field = value
            rebuildAiService()
        }

    private fun rebuildAiService() {
        aiService =
            if (apiKey != null) {
                AiService(
                    aiHost = host,
                    apiKey = apiKey!!,
                    customModelId = customModelId,
                    customBaseUrl = customBaseUrl,
                    customHeaders = customHeaders,
                )
            } else {
                null
            }
    }

    suspend fun translateLyrics(
        inputLyrics: Lyrics,
        targetLanguage: String,
    ): Result<Lyrics> =
        runCatching {
            val result = aiService?.translateLyrics(inputLyrics, targetLanguage)
                ?: throw IllegalStateException("AI service is not initialized. Please set host and apiKey.")

            // Validate: check that at least some lines were actually translated
            val originalWords = inputLyrics.lines?.map { it.words } ?: emptyList()
            val translatedWords = result.lines?.map { it.words } ?: emptyList()
            val unchangedCount = originalWords.zip(translatedWords).count { (orig, trans) -> orig == trans }
            val translatableCount = originalWords.count { it.trim().isNotEmpty() && it.trim() != "♫" }

            if (translatableCount > 0 && unchangedCount >= translatableCount) {
                throw IllegalStateException("Translation failed or returned empty lyrics.")
            }

            result
        }
}