package com.example.seoulhelper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Gemini {
    fun ask(apiKey: String, model: String, system: String, userText: String, jpegB64: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts", JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inline_data", JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put("data", jpegB64)
                                    )
                                )
                                .put(JSONObject().put("text", userText))
                        )
                )
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))

        val m = URLEncoder.encode(model.trim(), "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
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
                val hint = when (code) {
                    429 -> "\n(무료 한도를 다 썼어요. 잠시 후나 내일 다시 해 보세요)"
                    404 -> "\n(모델 이름을 확인해 주세요)"
                    else -> ""
                }
                throw RuntimeException("Gemini $code: ${msg.take(200)}$hint")
            }
            val candidates = JSONObject(respText).optJSONArray("candidates")
                ?: throw RuntimeException("Gemini가 빈 답을 보냈어요")
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw RuntimeException("Gemini 답을 읽지 못했어요")
            return buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
