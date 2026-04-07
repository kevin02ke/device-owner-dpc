package com.system.dpc

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.Toast

class DeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdmin::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Phase 7: Ultimate Defense Mechanisms
            
            // 1. Prevent booting into Safe Mode to protect against uninstallation
            // dpm.setSafeBootDisabled(adminComponent, true) // This method is not available in the standard Android SDK

            // 2. Lockdown the Status Bar / Quick Settings tray
            dpm.setStatusBarDisabled(adminComponent, true)

            // 3. Prevent Factory Reset from Settings
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)

            // 4. Prevent adding new users/profiles
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            
            // 5. Prevent app uninstallation (System level persistence)
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Cleanup or logging if the admin is revoked (though unlikely in Device Owner mode)
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
    }
}