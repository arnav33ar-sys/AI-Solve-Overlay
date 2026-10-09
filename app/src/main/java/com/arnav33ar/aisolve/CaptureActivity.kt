package com.arnav33ar.aisolve

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        } else {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = "CAPTURE_RESULT"
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startService(intent)
        }
        finish()
    }
}
