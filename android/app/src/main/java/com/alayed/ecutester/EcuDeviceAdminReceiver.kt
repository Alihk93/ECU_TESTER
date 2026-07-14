package com.alayed.ecutester

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin component. Only needed for the FULL kiosk lock (unattended, no exit):
 * make the app device owner once on a factory-fresh / account-free device with
 *   adb shell dpm set-device-owner com.alayed.ecutester/.EcuDeviceAdminReceiver
 * Then MainActivity.setupKiosk() allowlists itself for lock-task and pins the screen.
 * With no device owner set, everything else still works (autostart + immersive);
 * lock-task is simply skipped. See android/README.md.
 */
class EcuDeviceAdminReceiver : DeviceAdminReceiver()
