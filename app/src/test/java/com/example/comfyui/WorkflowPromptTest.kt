package com.example.comfyui

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class WorkflowPromptTest {
    @Test fun uiWorkflowIsRejectedBeforeParameterInjection() {
        val workflow = JsonParser.parseString("""{"id":"workflow-id","nodes":[],"links":[]}""").asJsonObject
        try {
            WorkflowPrompt.prepare(workflow)
            fail("UI format must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("节点界面格式"))
        }
    }

    @Test fun templateMetadataIsNotSubmittedAsANode() {
        val workflow = JsonParser.parseString("""{"_meta":{"title":"H3"},"19":{"class_type":"LoraLoaderModelOnly","inputs":{"model":["1",0],"strength_model":1.0}}}""").asJsonObject
        val prompt = WorkflowPrompt.prepare(workflow)
        assertFalse(prompt.has("_meta"))
        assertEquals("LoraLoaderModelOnly", prompt.getAsJsonObject("19").get("class_type").asString)
        prompt.getAsJsonObject("19").getAsJsonObject("inputs").addProperty("strength_model", 0.5)
        assertEquals(1.0, workflow.getAsJsonObject("19").getAsJsonObject("inputs").get("strength_model").asDouble, 0.0)
    }

    @Test fun invalidTopLevelValuesAreRejectedWithReadableErrors() {
        for (text in listOf("""{"id":"text"}""", """{"1":{"inputs":{}}}""", """{}""")) {
            try {
                WorkflowPrompt.prepare(JsonParser.parseString(text).asJsonObject)
                fail("Invalid workflow must be rejected")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
    }
}
