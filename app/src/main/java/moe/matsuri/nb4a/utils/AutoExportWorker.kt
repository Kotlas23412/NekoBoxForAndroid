package io.nekohasekai.sagernet.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.GeoIPHelper
import moe.matsuri.nb4a.utils.TestResult
import moe.matsuri.nb4a.utils.ExportNode
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AutoExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Берем настройки из глобального DataStore
        val token = DataStore.githubToken.trim()
        var repo = DataStore.githubRepo.trim()
        val fileName = DataStore.githubFilePath.trim().ifEmpty { "proxies.txt" }
        repo = repo.substringAfter("github.com/").trim('/')

        if (token.isEmpty() || repo.isEmpty()) return Result.failure()

        val groupId = inputData.getLong("groupId", -1L)
        if (groupId == -1L) return Result.failure()

        val mode = inputData.getString("mode") ?: "auto"
        val targetCount = inputData.getInt("targetCount", 10)

        val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
        if (profiles.isEmpty()) return Result.failure()

        showNotification(ctx, "🔄 Автообновление", "Проверка серверов...", inProgress = true)
        WhitelistHelper.loadLists()

        val workingProxies = ConcurrentLinkedQueue<TestResult>()
        val testedCount = AtomicInteger(0)
        val profilesQueue = ConcurrentLinkedQueue(profiles)

        coroutineScope {
            val testJobs = mutableListOf<Job>()
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest()
                    while (true) {
                        val profile = profilesQueue.poll() ?: break
                        try {
                            val ping = urlTest.doTest(profile)
                            if (ping > 0) {
                                val origName = profile.requireBean().name ?: "Unknown"
                                var countryCode = GeoIPHelper.extractCountryFromOriginalName(origName)
                                val serverAddr = profile.requireBean().serverAddress
                                if (countryCode == "Unknown") {
                                    val code = GeoIPHelper.detectCountryByIpOffline(serverAddr)
                                    countryCode = GeoIPHelper.buildBaseName(code)
                                }
                                var ipForCheck = serverAddr
                                try {
                                    if (!ipForCheck.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                                        ipForCheck = java.net.InetAddress.getByName(serverAddr).hostAddress ?: serverAddr
                                    }
                                } catch (_: Exception) {}
                                val isLte = WhitelistHelper.isIpInWhitelist(ipForCheck)
                                workingProxies.add(TestResult(profile, ping, countryCode, isLte))
                            }
                        } catch (_: Exception) {}

                        val current = testedCount.incrementAndGet()
                        if (current % 5 == 0) {
                            showNotification(ctx, "🔄 Автообновление", "Проверено: $current / ${profiles.size}", inProgress = true)
                        }
                    }
                })
            }
            testJobs.forEach { it.join() }
        }

        // Переименование и сортировка
        val exportList = GeoIPHelper.processAndRenameProxies(workingProxies.toList(), isCountryMode = (mode == "country"))

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            io.nekohasekai.sagernet.database.GroupManager.postReload(groupId)
        }

        var finalResult = exportList
        if (mode != "country") finalResult = finalResult.sortedBy { it.ping }.take(targetCount)

        if (finalResult.isEmpty()) {
            showNotification(ctx, "❌ Автообновление", "Не найдено рабочих серверов")
            return Result.failure()
        }

        val groupName = SagerDatabase.groupDao.getById(groupId)?.displayName() ?: "Конфигурация"

        // === ИСПОЛЬЗУЕМ ВСТРОЕННЫЙ МЕТОД ДЛЯ СТРОКОВЫХ ССЫЛОК ===
        // Извлекаем только текстовые ссылки
        val stringLinks = finalResult.map { it.link }

        val result = safeUploadLinksToGitHub(token, repo, fileName, groupName, stringLinks)

        if (result) {
            showNotification(ctx, "✅ Автообновление", "Отправлено ${finalResult.size} серверов")
            return Result.success()
        } else {
            showNotification(ctx, "❌ Ошибка", "Не удалось выгрузить на GitHub")
            return Result.retry()
        }
    }

    // Локальная защищенная функция загрузки (чтобы не конфликтовать с классами)
    private fun safeUploadLinksToGitHub(
        token: String,
        repo: String,
        path: String,
        groupName: String,
        links: List<String>
    ): Boolean {
        return try {
            val apiUrl = "https://api.github.com/repos/$repo/contents/$path"
            val client = OkHttpClient()

            val getReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            var currentText = ""
            var fileSha = ""

            client.newCall(getReq).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: "{}"
                    val json = JSONObject(bodyString)
                    fileSha = json.optString("sha", "")
                    val base64Content = json.optString("content", "").replace("\n", "")
                    if (base64Content.isNotEmpty()) {
                        currentText = String(Base64.decode(base64Content, Base64.DEFAULT))
                    }
                }
            }

            val safeBlockName = Regex.escape(groupName)
            val newGroupBlock = buildString {
                appendLine("# === BEGIN $groupName ===")
                for (link in links) {
                    appendLine(link)
                }
                append("# === END $groupName ===")
            }

            var updatedText = currentText
            if (updatedText.isEmpty()) {
                updatedText = newGroupBlock
            } else {
                val regex = Regex("# === BEGIN $safeBlockName ===.*?# === END $safeBlockName ===", RegexOption.DOT_MATCHES_ALL)
                if (updatedText.contains(regex)) {
                    updatedText = updatedText.replace(regex, newGroupBlock)
                } else {
                    updatedText = updatedText.trimEnd() + "\n\n" + newGroupBlock
                }
            }

            val cleanLines = updatedText.lines().filter { !it.startsWith("#") && it.contains("://") }

            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            val currentDate = sdf.format(Date())

            val rawTitle = "Всё рабочее (Тест) \uD83D\uDD16 $currentDate"
            val titleBase64 = Base64.encodeToString(rawTitle.toByteArray(), Base64.NO_WRAP)

            val headerBlock = buildString {
                appendLine("# profile-title: base64:$titleBase64")
                appendLine("# profile-update-interval: 1")
                appendLine("# Последнее обновление: $currentDate")
                appendLine("# Общее количество прокси: ${cleanLines.size}")
                appendLine("# Последние обновленные группы: $groupName")
                appendLine()
            }

            val textWithoutHeaders = updatedText.replace(Regex("(?s)^.*?# === BEGIN"), "# === BEGIN")
            val finalText = headerBlock + textWithoutHeaders
            val finalBase64 = Base64.encodeToString(finalText.toByteArray(), Base64.NO_WRAP)

            val putBody = JSONObject().apply {
                put("message", "Auto-update $groupName")
                put("content", finalBase64)
                if (fileSha.isNotEmpty()) put("sha", fileSha)
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(putReq).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun showNotification(ctx: Context, title: String, text: String, inProgress: Boolean = false) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "github_auto_export"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "Автообновление",
                    if (inProgress) android.app.NotificationManager.IMPORTANCE_LOW
                    else android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val icon = if (inProgress) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload_done
        val notification = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(inProgress)
            .setAutoCancel(!inProgress)
            .build()
        nm.notify(8890, notification)
    }
}