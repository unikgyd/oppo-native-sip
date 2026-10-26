package dev.mysip

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo

class SipApplication : Application() {
    // The SDK resolves Application from any supplied Context and derives resource
    // paths from Application.filesDir. Keep those resources available before unlock.
    // Account preferences still use the normal credential-encrypted application context.
    override fun getFilesDir() = baseContext.createDeviceProtectedStorageContext().filesDir
    override fun getCacheDir() = baseContext.createDeviceProtectedStorageContext().cacheDir
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name == "push_notification_storage") baseContext.createDeviceProtectedStorageContext().getSharedPreferences(name, mode)
        else super.getSharedPreferences(name, mode)
    override fun onCreate() {
        super.onCreate()
        SipRuntime.init(this)
        MessageNotifications.ensureChannel()
        if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ConfigStore(this).load().enabled) {
            SipRuntimeService.start(this)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_UNLOCKED) SystemSmsBridge.schedule()
        if (intent.action in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)
            && ConfigStore(context).load().enabled) SipRuntimeService.start(context)
    }
}
