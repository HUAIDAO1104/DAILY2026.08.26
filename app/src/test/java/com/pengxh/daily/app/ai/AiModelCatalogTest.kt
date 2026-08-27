package com.pengxh.daily.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelCatalogTest {

    @Test
    fun keepsAllModelsAndPrioritizesCompatibleRecommendation() {
        val models = parseAvailableModels(
            """
            {
              "data": [
                {"id":"anthropic-only","owned_by":"vendor-a","supported_endpoint_types":["anthropic"]},
                {"id":"qwen3-max","owned_by":"vendor-b","supported_endpoint_types":["openai","anthropic"]},
                {"id":"alpha-chat","owned_by":"vendor-c","supported_endpoint_types":["openai"]}
              ]
            }
            """.trimIndent()
        )

        assertEquals(3, models.size)
        assertEquals("qwen3-max", models.first().id)
        assertTrue(models.first().supportsOpenAi)
        assertEquals("anthropic-only", models.last().id)
        assertFalse(models.last().supportsOpenAi)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEmptyModelCatalog() {
        parseAvailableModels("{\"data\":[]}")
    }
}
