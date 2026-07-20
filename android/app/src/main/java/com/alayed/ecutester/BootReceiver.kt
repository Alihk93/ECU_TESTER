package com.alayed.ecutester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Autostart: launches the dashboard when the device finishes booting, so a kiosk
 * TV comes up straight into ECU_TESTER (docs/ANDROID_MIGRATION.md §6.1). Registered
 * for BOOT_COMPLETED in the manifest. On a dedicated appliance this is the primary
 * "boot into dashboard" mechanism; pairing it with device-owner lock-task (see
 * MainActivity.setupKiosk) makes it unattended.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Boot into the intro splash; ENTER hands off to the dashboard.
                val launch = Intent(context, IntroActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        }
    }
}
