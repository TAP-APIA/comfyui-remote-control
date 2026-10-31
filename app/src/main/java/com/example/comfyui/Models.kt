package com.example.comfyui

/**
 * 工作流：节点的连接方式（结构），由 JSON 文件导入。
 * json 字段保存完整的节点图（含节点间连线），参数值仅作为默认值，提交时会被参数配置覆盖。
 */
data class WorkflowItem(val name: String, val json: String)

/**
 * 参数配置：保存提示词分段及视频参数，prompt 保留拼接文本以兼容旧配置。
 * 提交任务时与工作流（节点结构）组合，注入后发送到服务端。
 */
data class ParamConfig(
    val name: String,
    val prompt: String,
    val duration: String,
    val steps: String,
    val width: String,
    val height: String,
    val promptSections: List<String>? = null
) {
    fun sections(): List<String> = promptSections?.takeIf { it.isNotEmpty() } ?: listOf(prompt)

    fun joinedPrompt(): String = sections().joinToString("\n\n")
}
