package com.example.ytblocker

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object IconToggle {
    private fun alias(ctx: Context) = ComponentName(ctx, "com.example.ytblocker.Launcher")

    fun isVisible(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun set(ctx: Context, visible: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            alias(ctx),
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

// الاتصال بالرمز *#*#8669#*#* من تطبيق الاتصال يرجّع الأيقونة (يشتغل على أغلب الأجهزة)
class SecretReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IconToggle.set(context, true)
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
