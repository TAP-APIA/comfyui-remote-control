package com.example.comfyui

import com.example.comfyui.network.RewriteClient
import com.example.comfyui.network.RewriteSettings
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RewriteClientTest {
    @Test fun acceptsBaseUrlsAndCompleteEndpoints() {
        assertEquals("https://example.com/v1/chat/completions", RewriteClient.endpoint("https://example.com"))
        assertEquals("https://example.com/v1/chat/completions", RewriteClient.endpoint("https://example.com/v1/"))
        assertEquals("https://example.com/custom/chat/completions", RewriteClient.endpoint("https://example.com/custom/chat/completions"))
    }

    @Test fun sendsOriginalAndInstructionsWithoutEmbeddingTheKey() {
        val body = RewriteClient.requestBody(RewriteSettings("https://example.com/v1", "test-secret", "model-a"), "风景\n原文", "写得简洁")
        assertEquals("model-a", body.get("model").asString)
        val content = JsonParser.parseString(body.getAsJsonArray("messages")[1].asJsonObject.get("content").asString).asJsonObject
        assertEquals("风景\n原文", content.get("原文").asString)
        assertEquals("写得简洁", content.get("改写要求").asString)
        assertFalse(body.toString().contains("test-secret"))
    }

    @Test fun extractsOnlyTheReplyTextAndPreservesItsNewlines() {
        assertEquals("新的描述\n第二行", RewriteClient.rewrittenText("""{"choices":[{"message":{"role":"assistant","content":"新的描述\n第二行"}}],"usage":{"total_tokens":100}}"""))
    }

    @Test fun rejectsEmptyOrUnsupportedReplies() {
        for (body in listOf("{}", """{"choices":[]}""", """{"choices":[{"message":{"content":null}}]}""")) {
            try {
                RewriteClient.rewrittenText(body)
                fail("No replacement should be produced")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
    }
}
