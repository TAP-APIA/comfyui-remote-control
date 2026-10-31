package com.example.comfyui

import com.google.gson.JsonObject

object WorkflowPrompt {
    fun prepare(workflow: JsonObject): JsonObject {
        require(!workflow.get("nodes").let { it != null && it.isJsonArray }) {
            "这是节点界面格式，请导入「ComfyUI远程控制专用」工作流，或从 ComfyUI 导出 API 格式"
        }
        val prompt = JsonObject()
        for ((id, value) in workflow.entrySet()) {
            if (id.startsWith("_")) continue
            require(value.isJsonObject) { "节点 $id 不是 API 节点对象" }
            val node = value.asJsonObject
            require(node.get("class_type")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true &&
                node.get("inputs")?.isJsonObject == true) {
                "节点 $id 缺少 class_type 或 inputs，请使用 API 格式工作流"
            }
            prompt.add(id, node.deepCopy())
        }
        require(prompt.size() > 0) { "工作流没有可执行节点" }
        return prompt
    }
}
