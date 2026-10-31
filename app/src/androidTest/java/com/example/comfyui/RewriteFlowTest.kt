package com.example.comfyui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.comfyui.network.RewriteSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RewriteFlowTest {
    private class ModelServer(val status: Int, val body: String) : AutoCloseable {
        val socket = ServerSocket(0)
        val received = CountDownLatch(1)
        var request: JsonObject? = null
        var authorization: String? = null
        var path: String? = null
        val thread = Thread {
            socket.accept().use { connection ->
                val input = connection.getInputStream().buffered()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    check(next >= 0)
                    header.append(next.toChar())
                }
                val lines = header.toString().split("\r\n")
                path = lines.first().split(" ")[1]
                authorization = lines.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(":")?.trim()
                val length = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(":").trim().toInt()
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(bytes, offset, length - offset)
                    check(read > 0)
                    offset += read
                }
                request = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
                val response = body.toByteArray(Charsets.UTF_8)
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
                received.countDown()
            }
        }.apply { isDaemon = true; start() }

        override fun close() { socket.close(); thread.join(1000) }
    }

    private fun setup(scenario: ActivityScenario<MainActivity>, server: ModelServer) {
        scenario.onActivity { activity ->
            val store = RewriteSettingsStore(activity)
            store.save(RewriteSettings("http://127.0.0.1:${server.socket.localPort}/v1", "mock-key-only", "test-model"))
            assertEquals("mock-key-only", store.load().apiKey)
            assertFalse(activity.getSharedPreferences("llm_settings", 0).all.values.any { it.toString().contains("mock-key-only") })
            ViewModelProvider(activity)[MainViewModel::class.java].setParamConfig(
                ParamConfig("改写测试", "原始风景\n\n保留这一段", "5", "12", "640", "352", listOf("原始风景", "保留这一段")))
        }
        onView(withContentDescription("改写第1段")).perform(scrollTo(), click())
        onView(withHint("例如：保持原意，写得更简洁")).perform(replaceText("描述夕阳，保持简洁"), closeSoftKeyboard())
        onView(withText("开始改写")).perform(click())
        assertTrue("Model request not received", server.received.await(10, TimeUnit.SECONDS))
    }

    @Test fun replyReplacesOnlyTheSelectedSectionAndCanBeUndone() {
        ModelServer(200, """{"choices":[{"message":{"content":"夕阳照耀山谷"}}]}""").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setup(scenario, server)
                var ready = false
                for (attempt in 0 until 50) {
                    scenario.onActivity { ready = ViewModelProvider(it)[MainViewModel::class.java].currentParams.value?.sections()?.first() == "夕阳照耀山谷" }
                    if (ready) break
                    Thread.sleep(100)
                }
                assertTrue("Reply was not applied", ready)
                onView(withContentDescription("提示词第1段")).check(matches(withText("夕阳照耀山谷")))
                onView(withContentDescription("提示词第2段")).check(matches(withText("保留这一段")))
                assertEquals("/v1/chat/completions", server.path)
                assertEquals("Bearer mock-key-only", server.authorization)
                val sent = JsonParser.parseString(server.request!!.getAsJsonArray("messages")[1].asJsonObject.get("content").asString).asJsonObject
                assertEquals("原始风景", sent.get("原文").asString)
                assertEquals("描述夕阳，保持简洁", sent.get("改写要求").asString)
                onView(withContentDescription("撤销第1段改写")).perform(scrollTo(), click())
                onView(withContentDescription("提示词第1段")).check(matches(withText("原始风景")))
            }
        }
    }

    @Test fun httpFailureKeepsTheOriginalText() {
        ModelServer(401, """{"error":{"message":"test unauthorized"}}""").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setup(scenario, server)
                for (attempt in 0 until 50) {
                    var enabled = false
                    scenario.onActivity { enabled = it.findViewById<android.view.View>(R.id.submit_button).isEnabled }
                    if (enabled) break
                    Thread.sleep(100)
                }
                onView(withText("改写未完成")).check(matches(isDisplayed()))
                onView(withText("确定")).perform(click())
                onView(withContentDescription("提示词第1段")).check(matches(withText("原始风景")))
            }
        }
    }
}
