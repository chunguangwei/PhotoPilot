package dev.photopilot.scaffold.data.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DiagnosticsExporter(context: Context) {

    private val appContext = context.applicationContext

    fun exportJson(errorCode: String?, errorClass: String?): String {
        val info = packageInfo()
        return buildString {
            append("{\n")
            append("  \"app_version_name\": ").append(jsonString(info?.versionName)).append(",\n")
            append("  \"app_version_code\": ").append(info?.longVersionCode ?: -1L).append(",\n")
            append("  \"android_api\": ").append(Build.VERSION.SDK_INT).append(",\n")
            append("  \"schema_version\": ").append(PhotoPilotDatabase.SCHEMA_VERSION).append(",\n")
            append("  \"error_code\": ").append(jsonString(errorCode)).append(",\n")
            append("  \"error_type\": ").append(jsonString(errorClass)).append(",\n")
            append("  \"timestamp\": ").append(jsonString(timestamp())).append('\n')
            append("}\n")
        }
    }

    fun writeToCache(json: String): File {
        val dir = File(appContext.cacheDir, "diagnostics")
        dir.mkdirs()
        val file = File(dir, "photopilot-diagnostics-${System.currentTimeMillis()}.json")
        file.writeText(json)
        return file
    }

    fun buildShareIntent(json: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
        }

    private fun packageInfo(): PackageInfo? =
        runCatching {
            val pm = appContext.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, 0)
            }
        }.getOrNull()

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    companion object {
        const val ERROR_DB_MIGRATION_FAILED = "DB_MIGRATION_FAILED"
    }
}
