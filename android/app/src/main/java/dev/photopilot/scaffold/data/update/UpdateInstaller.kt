package dev.photopilot.scaffold.data.update

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File

object UpdateInstaller {

    fun apkUri(context: Context, file: File): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority("${context.packageName}.updatefiles")
        .appendPath(file.name)
        .build()

    fun installIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri(context, file), UpdateFileProvider.MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        context.startActivity(installIntent(context, file))
        return true
    }
}
