package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object WhitelistHelper {
    // Храним диапазоны (Начальный IP, Конечный IP) в виде чисел для супер-быстрой проверки
    private val cidrRanges = mutableListOf<Pair<Long, Long>>()
    private var isLoaded = false

    private val urls = listOf(
        "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/whitelist.txt",
        "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/ipwhitelist.txt"
    )

    suspend fun loadLists() {
        if (isLoaded) return
        withContext(Dispatchers.IO) {
            try {
                cidrRanges.clear()
                for (urlString in urls) {
                    try {
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        val text = conn.inputStream.bufferedReader().readText()

                        text.lines().forEach { line ->
                            val cleanLine = line.substringBefore("#").trim()
                            if (cleanLine.isNotEmpty()) {
                                parseCidr(cleanLine)?.let { cidrRanges.add(it) }
                            }
                        }
                    } catch (_: Exception) {}
                }
                isLoaded = true
            } catch (_: Exception) {}
        }
    }

    fun isIpInWhitelist(ipString: String): Boolean {
        if (!isLoaded || cidrRanges.isEmpty()) return false
        val ipLong = ipToLong(ipString) ?: return false

        for (range in cidrRanges) {
            if (ipLong in range.first..range.second) {
                return true
            }
        }
        return false
    }

    private fun parseCidr(cidr: String): Pair<Long, Long>? {
        try {
            val parts = cidr.split("/")
            val ip = parts[0]
            val prefix = if (parts.size > 1) parts[1].toInt() else 32
            val ipLong = ipToLong(ip) ?: return null

            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val startIp = ipLong and mask
            val endIp = startIp or mask.inv() and 0xFFFFFFFFL

            return Pair(startIp, endIp)
        } catch (_: Exception) {
            return null
        }
    }

    private fun ipToLong(ip: String): Long? {
        try {
            val parts = ip.split(".")
            if (parts.size != 4) return null
            var result = 0L
            for (i in 0..3) {
                result = (result shl 8) or parts[i].toLong()
            }
            return result
        } catch (_: Exception) {
            return null
        }
    }
}