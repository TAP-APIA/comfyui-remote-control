package com.example.comfyui.network

import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ComfyUiApi {
    @POST("prompt")
    fun postPrompt(@Body body: JsonObject): Call<JsonObject>

    @GET("queue")
    fun getQueue(): Call<JsonObject>

    @GET("history")
    fun getHistory(): Call<JsonObject>

    @GET("history/{prompt_id}")
    fun getHistoryById(@Path("prompt_id") promptId: String): Call<JsonObject>

    @POST("interrupt")
    fun interrupt(): Call<JsonObject>

    @POST("queue")
    fun deleteQueueItem(@Body body: JsonObject): Call<JsonObject>
}
