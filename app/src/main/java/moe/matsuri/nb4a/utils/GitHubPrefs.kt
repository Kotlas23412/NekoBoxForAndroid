package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.SharedPreferences

object GitHubPrefs {
    private const val PREF_NAME = "github_export_prefs"
    private const val KEY_TOKEN = "gh_token"
    private const val KEY_REPO = "gh_repo"
    private const val KEY_FILENAME = "gh_filename"

    // Ключи для автообновления
    private const val KEY_AUTO_INTERVAL = "gh_auto_interval"
    private const val KEY_AUTO_GROUP_ID = "gh_auto_group_id"
    private const val KEY_AUTO_MODE = "gh_auto_mode"
    private const val KEY_AUTO_COUNT = "gh_auto_count"

    private fun prefs(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getToken(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun getRepo(ctx: Context): String =
        prefs(ctx).getString(KEY_REPO, "") ?: ""

    fun getFileName(ctx: Context): String =
        prefs(ctx).getString(KEY_FILENAME, "proxies.txt") ?: "proxies.txt"

    fun save(ctx: Context, token: String, repo: String, fileName: String) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_REPO, repo)
            .putString(KEY_FILENAME, fileName)
            .apply()
    }

    // === ФУНКЦИИ АВТООБНОВЛЕНИЯ ===

    fun getAutoInterval(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AUTO_INTERVAL, 0) // 0 = выключено

    fun getAutoGroupId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_AUTO_GROUP_ID, -1L)

    fun getAutoMode(ctx: Context): String =
        prefs(ctx).getString(KEY_AUTO_MODE, "auto") ?: "auto"

    fun getAutoCount(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AUTO_COUNT, 10)

    fun saveAuto(ctx: Context, intervalHours: Int, groupId: Long, mode: String, count: Int) {
        prefs(ctx).edit()
            .putInt(KEY_AUTO_INTERVAL, intervalHours)
            .putLong(KEY_AUTO_GROUP_ID, groupId)
            .putString(KEY_AUTO_MODE, mode)
            .putInt(KEY_AUTO_COUNT, count)
            .apply()
    }

    fun scheduleAutoExport(ctx: Context) {
        val interval = getAutoInterval(ctx)
        val tag = "auto_github_export"

        val workManager = androidx.work.WorkManager.getInstance(ctx)
        workManager.cancelAllWorkByTag(tag)

        if (interval <= 0) return

        val data = androidx.work.Data.Builder()
            .putLong("groupId", getAutoGroupId(ctx))
            .putString("mode", getAutoMode(ctx))
            .putInt("targetCount", getAutoCount(ctx))
            .build()

        val request = androidx.work.PeriodicWorkRequestBuilder<AutoExportWorker>(
            interval.toLong(), java.util.concurrent.TimeUnit.HOURS
        )
            .setInputData(data)
            .addTag(tag)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            tag,
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }
}