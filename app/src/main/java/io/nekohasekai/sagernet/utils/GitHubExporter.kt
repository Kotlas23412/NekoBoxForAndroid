package io.nekohasekai.sagernet.utils

import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GitHubExporter {

    private val client = OkHttpClient()

    data class ExportResult(val success: Boolean, val message: String)

    /**
     * Выполняет "умный" экспорт прокси на GitHub.
     * Скачивает текущий файл, обновляет только нужную группу и заливает обратно.
     */
    suspend fun exportGroup(
        groupName: String,
        proxies: List<ProxyEntity>
    ): ExportResult = withContext(Dispatchers.IO) {

        val token = DataStore.githubToken.trim()
        val repo = DataStore.githubRepo.trim()
        val path = DataStore.githubFilePath.trim()

        if (token.isBlank() || repo.isBlank() || path.isBlank()) {
            return@withContext ExportResult(false, "Не указаны настройки GitHub (Token, Repo, Path)")
        }

        val apiUrl = "https://api.github.com/repos/$repo/contents/$path"

        try {
            // 1. Получаем текущий файл (чтобы узнать его SHA и текущий текст)
            val getReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            var currentText = ""
            var fileSha = ""
            var totalProxiesInFile = 0

            client.newCall(getReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body!!.string())
                    fileSha = json.optString("sha", "")
                    val base64Content = json.optString("content", "").replace("\n", "")
                    if (base64Content.isNotEmpty()) {
                        currentText = String(Base64.decode(base64Content, Base64.DEFAULT))
                    }
                } else if (response.code != 404) {
                    return@withContext ExportResult(false, "Ошибка получения файла: ${response.code}")
                }
            }

            // 2. Формируем новый блок прокси для текущей группы
            val newGroupBlock = buildString {␍␊
                appendLine("# === BEGIN $groupName ===")␍␊
                for (p in proxies) {␍␊
                    appendLine(p.toStdLink())
                }␍␊
                append("# === END $groupName ===")␍␊
            }␍␊

            // 3. Интегрируем блок в существующий текст
            val updatedText = if (currentText.isEmpty()) {
                newGroupBlock
            } else {
                val regex = Regex("# === BEGIN $groupName ===.*?# === END $groupName ===", RegexOption.DOT_MATCHES_ALL)
                if (currentText.contains(regex)) {
                    currentText.replace(regex, newGroupBlock)
                } else {
                    currentText + "\n\n" + newGroupBlock
                }
            }

            // 4. Считаем общее количество прокси (строки, начинающиеся с протоколов)
            val lines = updatedText.lines()
            val cleanLines = lines.filter { !it.startsWith("#") && it.contains("://") }
            totalProxiesInFile = cleanLines.size

            // 5. Генерируем ваши заголовки
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            val currentDate = sdf.format(Date())

            val rawTitle = "Всё рабочее (Тест) \uD83D\uDD16 $currentDate" // Base64 заголовок
            val titleBase64 = Base64.encodeToString(rawTitle.toByteArray(), Base64.NO_WRAP)
@@ -118,30 +123,136 @@ object GitHubExporter {

            // 6. Отправляем обновленный файл на GitHub
            val putBody = JSONObject().apply {
                put("message", "Auto-update group: $groupName")
                put("content", finalBase64)
                if (fileSha.isNotEmpty()) {
                    put("sha", fileSha)
                }
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putReq).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext ExportResult(true, "Успешно выгружено $totalProxiesInFile прокси на GitHub!")
                } else {
                    return@withContext ExportResult(false, "Ошибка отправки: ${response.code} ${response.body?.string()}")
                }
            }

        } catch (e: Exception) {
            return@withContext ExportResult(false, "Ошибка: ${e.message}")
        }
    }
}
