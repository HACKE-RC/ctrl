package com.example.ctrl.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo

object AppController {
    data class AppInfo(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
    )
    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    fun listLaunchablePackages(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        return activities.mapNotNull { it.activityInfo?.packageName }.distinct().sorted()
    }

    fun listInstalledApps(context: Context, includeSystem: Boolean, query: String?): List<AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val normalizedQuery = query?.trim()?.lowercase()

        return apps.map { app ->
            val label = pm.getApplicationLabel(app).toString()
            AppInfo(
                packageName = app.packageName,
                label = label,
                isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }.filter { info ->
            if (!includeSystem && info.isSystem) return@filter false
            if (normalizedQuery.isNullOrEmpty()) return@filter true
            val hay = (info.label + " " + info.packageName).lowercase()
            hay.contains(normalizedQuery)
        }.sortedBy { it.label.lowercase() }
    }
}
