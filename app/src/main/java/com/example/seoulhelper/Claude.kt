package com.example.seoulhelper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Claude {
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    fun ask(apiKey: String, model: String, system: String, userText: String, jpegB64: String): String {
        val image = JSONObject()
            .put("type", "image")
            .put(
                "source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", jpegB64)
            )
        val text = JSONObject().put("type", "text").put("text", userText)
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 500)
            .put("system", system)
            .put(
                "messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray().put(image).put(text))
                )
            )

        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(respText).getJSONObject("error").getString("message")
                }.getOrDefault(respText.take(200))
                throw RuntimeException("API $code: $msg")
            }
            val content = JSONObject(respText).getJSONArray("content")
            return buildString {
                for (i in 0 until content.length()) {
                    val c = content.getJSONObject(i)
                    if (c.optString("type") == "text") append(c.optString("text"))
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
