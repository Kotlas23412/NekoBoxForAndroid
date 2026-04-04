package io.nekohasekai.sagernet.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.nekohasekai.sagernet.utils.GitHubPrefs

object GitHubSettingsDialog {
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val tokenLabel = TextView(context).apply {
            text = "GitHub Token:"
            textSize = 14f
        }
        val tokenInput = EditText(context).apply {
            hint = "ghp_xxxxxxxxxxxx"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(GitHubPrefs.getToken(context))
            setSingleLine()
        }

        val repoLabel = TextView(context).apply {
            text = "Repository (username/repo):"
            textSize = 14f
        }
        val repoInput = EditText(context).apply {
            hint = "myname/my-proxies"
            setText(GitHubPrefs.getRepo(context))
            setSingleLine()
        }

        val fileLabel = TextView(context).apply {
            text = "File name:"
            textSize = 14f
        }
        val fileInput = EditText(context).apply {
            hint = "proxies.txt"
            setText(GitHubPrefs.getFileName(context))
            setSingleLine()
        }

        layout.addView(tokenLabel)
        layout.addView(tokenInput)
        layout.addView(repoLabel)
        layout.addView(repoInput)
        layout.addView(fileLabel)
        layout.addView(fileInput)

        AlertDialog.Builder(context)
            .setTitle("GitHub Export Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                GitHubPrefs.save(
                    context,
                    tokenInput.text.toString().trim(),
                    repoInput.text.toString().trim(),
                    fileInput.text.toString().trim().ifEmpty { "proxies.txt" }
                )
                onSaved?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}