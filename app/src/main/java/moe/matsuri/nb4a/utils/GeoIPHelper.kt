package moe.matsuri.nb4a.utils

import android.content.Context
import java.net.InetAddress
import java.util.Locale
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProfileManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

data class TestResult(val profile: ProxyEntity, val ping: Int, val countryCode: String, val isLte: Boolean)
data class ExportNode(val link: String, val ping: Int)

object GeoIPHelper {

    // =======================================================================
    // МАГИЧЕСКИЙ ДВИЖОК СИНХРОНИЗАЦИИ (КАЧАЕТ С GITHUB + ОФФЛАЙН ФОЛБЭК)
    // =======================================================================
    suspend fun syncWithGitHub(ctx: Context, groupId: Long): Int {
        var updatedCount = 0
        try {
            val token = io.nekohasekai.sagernet.utils.GitHubPrefs.getToken(ctx).trim()
            var repo = io.nekohasekai.sagernet.utils.GitHubPrefs.getRepo(ctx).trim()
            repo = repo.substringAfter("github.com/").trim('/')

            val groupProfiles = io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getByGroup(groupId)
            if (groupProfiles.isEmpty()) return 0

            val groupName = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(groupId)?.displayName() ?: "Конфигурация"
            val safeFileName = groupName.replace(Regex("[^A-Za-z0-9А-Яа-яЁё\\s\\-_]"), "").trim().replace(" ", "_")
            val fileName = "$safeFileName.txt"

            var githubFileText = ""
            if (token.isNotEmpty() && repo.isNotEmpty()) {
                try {
                    val url = java.net.URL("https://api.github.com/repos/$repo/contents/$fileName")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.connectTimeout = 5000
                    if (conn.responseCode == 200 || conn.responseCode == 201) {
                        val json = conn.inputStream.bufferedReader().readText()
                        val contentMatch = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"").find(json)
                        if (contentMatch != null) {
                            val cleanBase64 = contentMatch.groupValues[1].replace("\\n", "").replace("\n", "").replace("\\r", "")
                            githubFileText = String(android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT))
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }

            // Карта: ServerAddress:Port -> Идеальное Имя
            val idealNamesMap = mutableMapOf<String, String>()
            if (githubFileText.isNotEmpty()) {
                for (line in githubFileText.lines()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("vless://") && !trimmed.startsWith("trojan://") && !trimmed.startsWith("vmess://")) continue
                    val parts = trimmed.split("#")
                    if (parts.size < 2) continue
                    val idealName = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    val parsedList = io.nekohasekai.sagernet.group.RawUpdater.parseRaw(trimmed, idealName)
                    val parsedProfile = parsedList?.firstOrNull() ?: continue
                    idealNamesMap["${parsedProfile.serverAddress}:${parsedProfile.serverPort}"] = idealName
                }
            }

            // Загружаем белые списки для новых прокси
            io.nekohasekai.sagernet.utils.WhitelistHelper.loadLists()

            // Обновляем профили
            for (profile in groupProfiles) {
                val bean = profile.requireBean()
                val serverAddr = bean.serverAddress
                val port = bean.serverPort
                val currentName = bean.name ?: ""
                val key = "$serverAddr:$port"
                var newName = currentName

                if (idealNamesMap.containsKey(key)) {
                    newName = idealNamesMap[key]!!
                } else if (!currentName.contains(Regex("[\uD83C\uDDE6-\uD83C\uDDFF]{2}"))) {
                    var ip = serverAddr
                    try { if (!ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) && !ip.contains(":")) ip = java.net.InetAddress.getByName(serverAddr).hostAddress ?: serverAddr } catch (_: Exception) {}
                    val code = detectCountryByIpOffline(ip)
                    newName = if (code != "UN") buildBaseName(code) else "Неизвестно"
                    if (io.nekohasekai.sagernet.utils.WhitelistHelper.isIpInWhitelist(ip)) newName += " | VK | LTE"
                }

                newName = newName.replace(Regex(" #\\d+"), "") // Убираем старую нумерацию
                if (currentName != newName) {
                    bean.name = newName; profile.putBean(bean)
                    ProfileManager.updateProfile(profile)
                    updatedCount++
                }
            }
            if (updatedCount > 0) {
                GlobalScope.launch(Dispatchers.Main) {
                    io.nekohasekai.sagernet.database.GroupManager.postReload(groupId)
                }
            }
        } catch (_: Exception) {}
        return updatedCount
    }

    // =======================================================================
    // КОНВЕЙЕР ЭКСПОРТА
    // =======================================================================
    fun processAndRenameProxies(workingProxies: List<TestResult>, isCountryMode: Boolean = false): List<ExportNode> {
        val groupedByCountry = workingProxies.groupBy { it.countryCode }
        val exportList = mutableListOf<ExportNode>()
        for ((_, list) in groupedByCountry) {
            val sortedList = list.sortedBy { it.ping }
            val processList = if (isCountryMode) sortedList.take(1) else sortedList
            val needsNumbering = processList.size > 1
            for ((index, item) in processList.withIndex()) {
                var finalName = item.countryCode
                if (needsNumbering) finalName += " #${index + 1}"
                if (item.isLte) finalName += " | VK | LTE"
                val bean = item.profile.requireBean()
                if (bean.name != finalName) {
                    bean.name = finalName
                    item.profile.putBean(bean)
                    @kotlin.OptIn(DelicateCoroutinesApi::class)
                    GlobalScope.launch(Dispatchers.IO) {
                        try { ProfileManager.updateProfile(item.profile) } catch (_: Exception) {}
                    }
                }
                try {
                    val link = item.profile.toStdLink()
                    if (link.isNotBlank()) exportList.add(ExportNode(link, item.ping))
                } catch (_: Exception) {}
            }
        }
        return exportList
    }

    // =======================================================================
    // БАЗОВЫЕ ФУНКЦИИ
    // =======================================================================
    fun codeToFlag(code: String): String {
        val upper = code.uppercase(Locale.ROOT).trim()
        if (upper.length != 2 || upper == "UN") return ""
        return try {
            val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
            val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
            String(first) + String(second)
        } catch (_: Exception) { "" }
    }

    private val countryNames = mapOf("US" to "США", "GB" to "Великобритания", "DE" to "Германия", "FR" to "Франция", "NL" to "Нидерланды", "JP" to "Япония", "SG" to "Сингапур", "HK" to "Гонконг", "TW" to "Тайвань", "KR" to "Корея", "CA" to "Канада", "AU" to "Австралия", "RU" to "Россия", "UA" to "Украина", "TR" to "Турция", "IN" to "Индия", "BR" to "Бразилия", "IT" to "Италия", "ES" to "Испания", "SE" to "Швеция", "FI" to "Финляндия", "NO" to "Норвегия", "PL" to "Польша", "CZ" to "Чехия", "AT" to "Австрия", "CH" to "Швейцария", "IE" to "Ирландия", "DK" to "Дания", "RO" to "Румыния", "BG" to "Болгария", "HU" to "Венгрия", "IL" to "Израиль", "AE" to "ОАЭ", "ZA" to "ЮАР", "MX" to "Мексика", "AR" to "Аргентина", "CL" to "Чили", "CO" to "Колумбия", "TH" to "Таиланд", "VN" to "Вьетнам", "PH" to "Филиппины", "ID" to "Индонезия", "MY" to "Малайзия", "KZ" to "Казахстан", "LV" to "Латвия", "LT" to "Литва", "EE" to "Эстония", "GE" to "Грузия", "MD" to "Молдова", "BY" to "Беларусь", "AM" to "Армения", "AZ" to "Азербайджан", "PT" to "Португалия", "GR" to "Греция", "RS" to "Сербия", "SK" to "Словакия", "SI" to "Словения", "HR" to "Хорватия", "LU" to "Люксембург", "BE" to "Бельгия", "IS" to "Исландия", "CY" to "Кипр", "MT" to "Мальта")

    fun buildBaseName(code: String): String {
        val upper = code.uppercase(Locale.ROOT)
        if (upper == "UN") return "Неизвестно"
        val flag = try { String(Character.toChars(0x1F1E6 + (upper[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (upper[1] - 'A'))) } catch (_: Exception) { "" }
        val name = countryNames[upper] ?: upper
        return if (flag.isNotEmpty()) "$flag $name" else name
    }

    fun detectCountryByIpOffline(serverAddress: String): String {
        return try {
            var ip = serverAddress
            if (!ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) && !ip.contains(":")) {
                ip = InetAddress.getByName(serverAddress).hostAddress ?: return "UN"
            }
            val libcoreClass = Class.forName("libcore.Libcore")
            val method = libcoreClass.getMethod("geoIPCountry", String::class.java)
            val code = method.invoke(null, ip) as? String
            if (code.isNullOrBlank() || code == "ZZ") "UN" else code.uppercase(Locale.ROOT)
        } catch (_: Exception) { "UN" }
    }

    fun extractCountryFromOriginalName(originalName: String): String {
        val trimmed = originalName.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.codePointAt(0)
            if (first in 0x1F1E6..0x1F1FF) {
                val charCount1 = Character.charCount(first)
                if (trimmed.length > charCount1) {
                    val second = trimmed.codePointAt(charCount1)
                    if (second in 0x1F1E6..0x1F1FF) {
                        val charCount2 = Character.charCount(second)
                        val flag = trimmed.substring(0, charCount1 + charCount2)
                        val rest = trimmed.substring(charCount1 + charCount2).trim()
                        val firstWord = rest.split(Regex("[\\s\\-_|#]+"))[0].trim()
                        return if (firstWord.isNotEmpty()) "$flag $firstWord" else flag
                    }
                }
            }
        }
        val split = trimmed.split(Regex("[\\s\\-_|#]+"))
        return if (split.isNotEmpty() && split[0].isNotBlank()) split[0] else "Unknown"
    }
}