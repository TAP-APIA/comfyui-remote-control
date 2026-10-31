package com.example.comfyui.network

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID

class ComfyUiClient(val baseUrl: String) {
    private val client = OkHttpClient()
    val clientId = UUID.randomUUID().toString()
    private var webSocket: WebSocket? = null
    private var isConnected = false

    enum class TaskStatus {
        CONNECTED, DISCONNECTED, EXECUTING, EXECUTED, FINISHED, ERROR
    }

    interface ProgressListener {
        fun onProgress(node: String?, value: Int, max: Int)
        fun onStatusUpdate(status: TaskStatus, message: String?, nodeId: String? = null)
        fun onImageReady(filename: String, subfolder: String, type: String)
        fun onPreviewReady(bytes: ByteArray)
    }

    var listener: ProgressListener? = null

    fun isConnected() = isConnected

    fun connect() {
        if (isConnected) return
        try {
            val sanitizedBaseUrl = if (!baseUrl.startsWith("http")) "http://$baseUrl" else baseUrl
            val wsUrl = sanitizedBaseUrl.replace("http", "ws") + "/ws?clientId=$clientId"
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    listener?.onStatusUpdate(TaskStatus.CONNECTED, "已连接")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val byteArray = bytes.toByteArray()
                    if (byteArray.size > 8) {
                        listener?.onPreviewReady(byteArray.copyOfRange(8, byteArray.size))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    listener?.onStatusUpdate(TaskStatus.DISCONNECTED, "连接关闭")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    listener?.onStatusUpdate(TaskStatus.ERROR, t.message)
                }
            })
        } catch (e: Exception) {
            isConnected = false
            listener?.onStatusUpdate(TaskStatus.ERROR, e.message)
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "status" -> {
                    val data = json.optJSONObject("data")
                    val status = data?.optJSONObject("status")
                    val execInfo = status?.optJSONObject("exec_info")
                    val remaining = execInfo?.optInt("queue_remaining") ?: 0
                    listener?.onStatusUpdate(TaskStatus.EXECUTED, "队列剩余: $remaining")
                }
                "progress" -> {
                    val data = json.optJSONObject("data")
                    listener?.onProgress(data?.optString("node"), data?.optInt("value") ?: 0, data?.optInt("max") ?: 0)
                }
                "executing" -> {
                    val data = json.optJSONObject("data")
                    val node = data?.optString("node")
                    if (node != null && node != "null") {
                        listener?.onStatusUpdate(TaskStatus.EXECUTING, "正在执行节点", node)
                    } else {
                        listener?.onStatusUpdate(TaskStatus.FINISHED, "工作流执行完毕")
                    }
                }
                "executed" -> {
                    val data = json.optJSONObject("data")
                    val output = data?.optJSONObject("output")
                    val vids = output?.optJSONArray("gifs") ?: output?.optJSONArray("videos") ?: output?.optJSONArray("images")
                    if (vids != null && vids.length() > 0) {
                        val first = vids.getJSONObject(0)
                        listener?.onImageReady(first.getString("filename"), first.optString("subfolder", ""), first.optString("type", "output"))
                    }
                }
            }
        } catch (e: Exception) {}
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        isConnected = false
    }
}
