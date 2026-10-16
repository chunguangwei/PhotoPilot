package dev.photopilot.scaffold.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object PhotoPermissionGate {

    enum class Status { GRANTED, PARTIAL, DENIED }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun status(context: Context): Status {
        val required = requiredPermissions()
        val grantedCount = required.count {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (grantedCount == required.size) {
            return Status.GRANTED
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return Status.PARTIAL
        }
        if (grantedCount > 0) {
            return Status.PARTIAL
        }
        return Status.DENIED
    }
}
