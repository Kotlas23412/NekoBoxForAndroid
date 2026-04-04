package io.nekohasekai.sagernet.utils

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object old_GitHubExporter {
    suspend fun uploadToGitHub(
        token: String,
        repo: String,
        fileName: String,
        content: String,
        commitMessage: String = "proxy export"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api.github.com/repos/$repo/contents/$fileName"
            val url = URL(apiUrl)
            // Проверяем существует ли файл (нужен sha для обновления)
            var sha: String? = null
            try {
                val getConn = URL(apiUrl).openConnection() as HttpURLConnection
                getConn.setRequestProperty("Authorization", "Bearer $token")
                getConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (getConn.responseCode == 200) {
                    val resp = getConn.inputStream.bufferedReader().readText()
                    sha = JSONObject(resp).optString("sha", null)
                }
                getConn.disconnect()
            } catch (_: Exception) {
            }
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("message", commitMessage)
                put(
                    "content", Base64.encodeToString(
                        content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    )
                )
                if (sha != null) put("sha", sha)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().readText()
                val htmlUrl = JSONObject(resp)
                    .optJSONObject("content")
                    ?.optString("html_url", "OK") ?: "OK"
                Result.success(htmlUrl)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "error"
                Result.failure(Exception("HTTP $code: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportProxyLinks(
        token: String,
        repo: String,
        links: List<String>,
        fileName: String = "proxies.txt"
    ): Result<String> {
        val content = links.joinToString("\n")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return uploadToGitHub(
            token = token,
            repo = repo,
            fileName = fileName,
            content = content,
            commitMessage = "Update proxies $ts"
        )
    }
}