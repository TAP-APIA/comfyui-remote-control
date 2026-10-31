package com.example.comfyui.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class RewriteSettings(val address: String, val apiKey: String, val model: String)

object RewriteClient {
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    fun endpoint(address: String): String {
        val url = address.trim().trimEnd('/').toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "API 地址中不要包含账号或密钥" }
        if (url.encodedPath.endsWith("/chat/completions")) return url.toString()
        val suffix = if (url.encodedPath == "/") "v1/chat/completions" else "chat/completions"
        return url.newBuilder().addPathSegments(suffix).build().toString()
    }

    fun requestBody(settings: RewriteSettings, original: String, instruction: String): JsonObject {
        require(settings.model.isNotBlank()) { "请填写模型名称" }
        require(original.isNotBlank()) { "这段提示词为空" }
        require(instruction.isNotBlank()) { "请输入改写要求" }
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", "你是文本改写助手。按用户的改写要求处理原文。只输出改写后的正文，不要解释、前言、Markdown 代码块或 JSON 包装。")
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", JsonObject().apply {
                    addProperty("改写要求", instruction)
                    addProperty("原文", original)
                }.toString())
            })
        }
        return JsonObject().apply {
            addProperty("model", settings.model.trim())
            add("messages", messages)
            addProperty("stream", false)
        }
    }

    fun createCall(settings: RewriteSettings, original: String, instruction: String): Call {
        val request = Request.Builder().url(endpoint(settings.address))
            .post(requestBody(settings, original, instruction).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (settings.apiKey.isNotBlank()) request.header("Authorization", "Bearer ${settings.apiKey.trim()}")
        return client.newCall(request.build())
    }

    fun rewrittenText(body: String): String {
        val response = JsonParser.parseString(body).asJsonObject
        val choices = response.getAsJsonArray("choices")
        require(choices != null && choices.size() > 0) { "接口未返回 choices，请确认它支持 Chat Completions" }
        val content = choices[0].asJsonObject.getAsJsonObject("message")?.get("content")
        require(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString && content.asString.isNotBlank()) {
            "模型未返回可替换的正文，原文已保留"
        }
        return content.asString
    }
}
