package com.xelth.eckwms_movfast.xelixir

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Transparent activity that obtains the one-time MediaProjection grant (and the
 * POST_NOTIFICATIONS runtime permission on Android 13+) and then hands the grant
 * to [XelixirAgentService]. Finishes immediately after.
 *
 * On a managed/device-owner PDA this dialog can be auto-confirmed by the MDM;
 * on stock Android 13 it shows once and the projection is reusable.
 */
class XelixirConsentActivity : ComponentActivity() {

    companion object {
        private const val TAG = "XelixirConsent"
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "projection granted")
                XelixirAgentService.start(this, result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "projection denied")
                Toast.makeText(this, "Доступ к экрану отклонён", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Proceed regardless; notification is best-effort.
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
