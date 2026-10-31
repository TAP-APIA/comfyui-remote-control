package com.example.comfyui

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ParamConfigTest {
    private val gson = Gson()

    @Test fun oldSavedParamsKeepTheirFullPrompt() {
        val old = """{"name":"旧参数","prompt":"第一行\n\n第二行","duration":"5","steps":"12","width":"640","height":"352"}"""
        val config = gson.fromJson(old, ParamConfig::class.java)
        assertEquals(listOf("第一行\n\n第二行"), config.sections())
        assertEquals("第一行\n\n第二行", config.joinedPrompt())
    }

    @Test fun saveAndLoadKeepSectionOrderAndVideoParams() {
        val sections = listOf("风景\n日落", "  镜头缓慢移动  ", "自然环境声")
        val config = ParamConfig("分段参数", sections.joinToString("\n\n"), "5", "12", "640", "352", sections)
        val loaded = gson.fromJson(gson.toJson(config), ParamConfig::class.java)
        assertEquals(config, loaded)
        assertEquals("风景\n日落\n\n  镜头缓慢移动  \n\n自然环境声", loaded.joinedPrompt())
    }

    @Test fun sectionDataIsUsedWhenLegacyJoinedTextIsStale() {
        val config = ParamConfig("参数", "旧文本", "5", "12", "640", "352", listOf("第二段", "第一段"))
        assertEquals("第二段\n\n第一段", config.joinedPrompt())
    }
}
