package com.hiwtata.melique.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object Access {
    fun audioPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= 28) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return permissions.toTypedArray()
    }

    fun hasAudio(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return granted(context, permission)
    }

    /**
     * Silent move-to-trash (and therefore the 5-second undo) needs a real write
     * to the music file. Scoped storage on API 29+ blocks that unless the app
     * holds All Files Access. Below Android 10, the legacy write permission is enough.
     */
    fun canUndoDelete(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT <= 28 -> granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> false
        }
    }

    fun needsAllFilesEntry(): Boolean = Build.VERSION.SDK_INT >= 30

    fun allFilesIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < 30) {
            return detailsIntent(context)
        }
        val packageUri = Uri.parse("package:${context.packageName}")
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        return if (specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun detailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }

    private fun granted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
