package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

class GitHubExportSettingsFragment : SettingsPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.github_export_preferences, rootKey)

        // Связываем UI-настройки с нашими переменными из DataStore
        val tokenPref = findPreference<EditTextPreference>("github_token")
        tokenPref?.text = DataStore.githubToken
        tokenPref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.githubToken = newValue.toString()
            true
        }

        val repoPref = findPreference<EditTextPreference>("github_repo")
        repoPref?.text = DataStore.githubRepo
        repoPref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.githubRepo = newValue.toString()
            true
        }

        val pathPref = findPreference<EditTextPreference>("github_file_path")
        pathPref?.text = DataStore.githubFilePath
        pathPref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.githubFilePath = newValue.toString()
            true
        }

        val limitPref = findPreference<EditTextPreference>("github_export_limit")
        limitPref?.text = DataStore.githubExportLimit.toString()
        limitPref?.setOnPreferenceChangeListener { _, newValue ->
            val limit = newValue.toString().toIntOrNull() ?: 10
            DataStore.githubExportLimit = limit
            true
        }
    }
}