package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil.gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.suspendCoroutine

data class FullTestResult(
    val success: Boolean,
    val bestLatencyMs: Long,
    val successCount: Int,
    val totalCount: Int,
    val details: List<FullTestDetail>,
    val error: String?
) {
    fun summary(): String = if (success) {
        "HTTPS ${bestLatencyMs}ms ($successCount/$totalCount)"
    } else {
        "HTTPS FAIL: ${error?.take(50) ?: "unknown"}"
    }

    fun report(): String = buildString {
        if (success) {
            appendLine("✅ HTTPS test passed")
            appendLine("Best latency: ${bestLatencyMs} ms")
        } else {
            appendLine("❌ HTTPS test failed")
        }
        appendLine("Success: $successCount / $totalCount")
        appendLine()
        for (d in details) {
            val icon = if (d.success) "✅" else "❌"
            appendLine("$icon ${d.label}")
            if (d.success) {
                appendLine("   ${d.latencyMs} ms  HTTP ${d.statusCode}")
            } else {
                appendLine("   ${d.error}")
            }
        }
    }
}

data class FullTestDetail(
    val url: String,
    val label: String,
    val success: Boolean,
    val latencyMs: Long,
    val statusCode: Int,
    val error: String?
)

private data class HttpsTarget(
    val url: String,
    val label: String,
    val expectBody: String? = null
)

private val HTTPS_TARGETS = listOf(
    HttpsTarget("https://cp.cloudflare.com/", "Cloudflare", "ok"),
    HttpsTarget("https://www.google.com/generate_204", "Google 204"),
    HttpsTarget("https://www.gstatic.com/generate_204", "GStatic 204"),
    HttpsTarget("https://detectportal.firefox.com/success.txt", "Firefox", "success"),
    HttpsTarget("https://www.apple.com/library/test/success.html", "Apple"),
    HttpsTarget("https://clients3.google.com/generate_204", "Google Clients"),
)

class FullTestInstance(
    profile: ProxyEntity,
    private val timeout: Int = 15000,
    private val minOk: Int = 2
) : BoxInstance(profile) {

    private var socksPort: Int = 0

    suspend fun doTest(): FullTestResult {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        init()
                        launch()

                        if (processes.processCount > 0) {
                            delay(500)
                        }
                        delay(300)

                        val result = runHttpsTests()
                        c.tryResume(result)
                    } catch (e: Exception) {
                        Logs.w("FullTest error: ${e.message}")
                        c.tryResume(
                            FullTestResult(
                                success = false,
                                bestLatencyMs = -1,
                                successCount = 0,
                                totalCount = 0,
                                details = emptyList(),
                                error = "${e.javaClass.simpleName}: ${e.message}"
                            )
                        )
                    }
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
        socksPort = mkPort()
        injectSocksInbound()
    }

    override suspend fun loadConfig() {
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectSocksInbound() {
        try {
            val configMap = gson.fromJson(
                config.config,
                mutableMapOf<String, Any>()::class.java
            ) as MutableMap<String, Any>

            val socksInbound = linkedMapOf<String, Any>(
                "type" to "socks",
                "tag" to "socks-full-test",
                "listen" to LOCALHOST,
                "listen_port" to socksPort.toDouble()
            )

            val inbounds = configMap["inbounds"]
            when (inbounds) {
                is MutableList<*> -> {
                    (inbounds as MutableList<Any>).add(socksInbound)
                }
                is List<*> -> {
                    val mutList = inbounds.toMutableList()
                    mutList.add(socksInbound)
                    configMap["inbounds"] = mutList
                }
                else -> {
                    configMap["inbounds"] = mutableListOf(socksInbound)
                }
            }

            config = ConfigBuildResult(
                gson.toJson(configMap),
                config.externalIndex,
                config.mainEntId,
                config.trafficMap,
                config.profileTagMap,
                config.selectorGroupId
            )

            if (BuildConfig.DEBUG) {
                Logs.d("FullTest: SOCKS inbound on port $socksPort")
            }
        } catch (e: Exception) {
            Logs.w("FullTest: inject failed: ${e.message}")
        }
    }

    private fun runHttpsTests(): FullTestResult {
        if (socksPort <= 0) {
            return FullTestResult(
                success = false, bestLatencyMs = -1,
                successCount = 0, totalCount = 0,
                details = emptyList(),
                error = "SOCKS port not available"
            )
        }

        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(LOCALHOST, socksPort)
        )

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeout.toLong() + 5000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        val details = mutableListOf<FullTestDetail>()
        var okCount = 0

        try {
            for (target in HTTPS_TARGETS) {
                if (okCount >= minOk && details.size >= minOk + 1) break

                val detail = probeOne(client, target)
                details.add(detail)
                if (detail.success) okCount++

                if (BuildConfig.DEBUG) {
                    val icon = if (detail.success) "✓" else "✗"
                    Logs.d("FullTest $icon ${detail.label}: ${detail.latencyMs}ms ${detail.error ?: ""}")
                }
            }
        } finally {
            try {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } catch (_: Exception) {
            }
        }

        val bestMs = details.filter { it.success }.minOfOrNull { it.latencyMs } ?: -1L

        return FullTestResult(
            success = okCount > 0,
            bestLatencyMs = bestMs,
            successCount = okCount,
            totalCount = details.size,
            details = details,
            error = if (okCount == 0) {
                details.firstOrNull()?.error ?: "All HTTPS tests failed"
            } else null
        )
    }

    private fun probeOne(client: OkHttpClient, target: HttpsTarget): FullTestDetail {
        return try {
            val request = Request.Builder()
                .url(target.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/125.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "close")
                .build()

            val t0 = System.nanoTime()
            val response = client.newCall(request).execute()
            val ms = (System.nanoTime() - t0) / 1_000_000

            val code = response.code
            val body = try {
                response.body?.string()?.take(1024)
            } catch (_: Exception) {
                null
            }
            response.close()

            val ok = when {
                code == 204 -> true
                code in 200..299 -> {
                    if (target.expectBody != null && body != null)
                        body.contains(target.expectBody, ignoreCase = true)
                    else true
                }
                code in 300..399 -> true
                else -> false
            }

            FullTestDetail(
                target.url, target.label, ok, ms, code,
                if (!ok) "HTTP $code" else null
            )
        } catch (e: IOException) {
            FullTestDetail(
                target.url, target.label, false, -1, -1,
                "${e.javaClass.simpleName}: ${e.message}"
            )
        } catch (e: Exception) {
            FullTestDetail(
                target.url, target.label, false, -1, -1,
                e.message ?: e.javaClass.simpleName
            )
        }
    }
}