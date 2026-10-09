package com.arnav33ar.aisolve

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var tvAnswer: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnStop: Button
    private lateinit var btnRetry: Button

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val okHttpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        setupOverlay()
    }

    private fun startForegroundService() {
        val channelId = "ai_solve_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("AI Solve Active")
            .setContentText("Overlay is running.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        setupDrag(overlayView.findViewById(R.id.drag_handle), params)

        overlayView.findViewById<Button>(R.id.btn_close).setOnClickListener { stopSelf() }
        
        btnCapture = overlayView.findViewById(R.id.btn_capture)
        btnStop = overlayView.findViewById(R.id.btn_stop)
        btnRetry = overlayView.findViewById(R.id.btn_retry)
        tvAnswer = overlayView.findViewById(R.id.tv_answer)

        btnCapture.setOnClickListener {
            btnCapture.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            tvAnswer.text = "Waiting for permission..."
            
            val intent = Intent(this, CaptureActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        btnStop.setOnClickListener { stopCapture() }
        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            btnCapture.visibility = View.VISIBLE
            tvAnswer.text = "Ready to capture."
        }

        windowManager.addView(overlayView, params)
    }

    private fun setupDrag(handle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    
                    if(params.x < 0) params.x = 0
                    if(params.y < 0) params.y = 0
                    
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CAPTURE_RESULT") {
            val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            if (code == Activity.RESULT_OK && data != null) {
                tvAnswer.text = "Capturing screen..."
                startProjection(code, data)
            } else {
                tvAnswer.text = "Permission denied."
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(code, data)
        
        val metrics = DisplayMetrics()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getMetrics(metrics)
        
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                imageReader?.setOnImageAvailableListener(null, null)
                
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                
                image.close()
                stopProjection()
                
                tvAnswer.post { tvAnswer.text = "Processing image..." }
                sendToBackend(croppedBitmap)
            }
        }, null)
    }

    private fun sendToBackend(bitmap: Bitmap) {
        scope.launch {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Str = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val backendUrl = prefs.getString("BACKEND_URL", "")?.trim()?.removeSuffix("/") ?: ""
                
                if (backendUrl.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvAnswer.text = "Error: Backend URL not set in app."
                        btnRetry.visibility = View.VISIBLE
                        btnStop.visibility = View.GONE
                    }
                    return@launch
                }

                val json = JSONObject().apply { put("imageBase64", base64Str) }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url(f"{backendUrl}/api/solve")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val respString = response.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            val jsonObj = JSONObject(respString)
                            tvAnswer.text = jsonObj.optString("answer", "No answer found.")
                        } else {
                            tvAnswer.text = f"Server Error: {response.code}\n{respString}"
                        }
                        stopCapture()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvAnswer.text = f"Network Error: {e.message}"
                    stopCapture()
                }
            }
        }
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun stopCapture() {
        stopProjection()
        btnCapture.visibility = View.VISIBLE
        btnStop.visibility = View.GONE
        btnRetry.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        scope.cancel()
        if (hasattr(this, 'overlayView') && overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }
}
