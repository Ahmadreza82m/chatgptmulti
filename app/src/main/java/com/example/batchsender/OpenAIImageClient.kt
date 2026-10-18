package com.example.batchsender

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object OpenAIImageClient {
    data class Result(val bytes: ByteArray)
    class ApiException(val code: Int, message: String): Exception("HTTP $code: $message")

    fun edit(context: Context, apiKey: String, model: String, image: Uri, prompt: String): Result {
        val boundary = "----BatchStudio${UUID.randomUUID()}"
        val conn = (URL("https://api.openai.com/v1/images/edits").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { out ->
            fun field(name: String, value: String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
            field("model", model); field("prompt", prompt); field("size", "auto")
            val bytes = context.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: error("Cannot read image")
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"input.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            out.write(bytes); out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw ApiException(code, body.take(800))
        val data = JSONObject(body).optJSONArray("data") ?: error("API returned no image data")
        val b64 = data.optJSONObject(0)?.optString("b64_json").orEmpty()
        if (b64.isBlank()) error("API response did not contain b64_json")
        return Result(Base64.decode(b64, Base64.DEFAULT))
    }
}
