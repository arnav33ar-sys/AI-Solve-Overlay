package com.arnav33ar.aisolve

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val etUrl = EditText(this).apply {
            hint = "Enter Backend URL (e.g. https://your-app.onrender.com)"
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            setText(prefs.getString("BACKEND_URL", ""))
        }
        
        val btnSaveUrl = Button(this).apply {
            text = "Save URL"
            setOnClickListener {
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putString("BACKEND_URL", etUrl.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
            }
        }
        
        val btnStart = Button(this).apply {
            text = "Start Floating Overlay"
            setOnClickListener {
                if (checkOverlayPermission()) {
                    val intent = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    finish()
                } else {
                    requestOverlayPermission()
                }
            }
        }

        layout.addView(etUrl)
        layout.addView(btnSaveUrl)
        layout.addView(btnStart)
        setContentView(layout)
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, 1001)
    }
}
