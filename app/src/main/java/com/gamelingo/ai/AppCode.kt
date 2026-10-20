package com.gamelingo.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class GameLingoApp : android.app.Application()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isRunning by remember { mutableStateOf(false) }
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                if (res.resultCode == RESULT_OK && res.data != null) {
                    val intent = Intent(this, ScreenCaptureOverlayService::class.java).apply {
                        putExtra("CODE", res.resultCode)
                        putExtra("DATA", res.data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                    else startService(intent)
                    isRunning = true
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0C13)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("GAMELINGO AI", color = Color(0xFF00E5FF), fontSize = 28.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (!isRunning) {
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                } else {
                                    launcher.launch(projectionManager.createScreenCaptureIntent())
                                }
                            } else {
                                stopService(Intent(this@MainActivity, ScreenCaptureOverlayService::class.java))
                                isRunning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFFF0055) else Color(0xFF9D00FF)),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isRunning) "หยุดการทำงาน" else "เริ่มเปิดปุ่มแปลภาษาลอยหน้าจอ")
                    }
                }
            }
        }
    }
}

class ScreenCaptureOverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var hud: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var hudTextView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val channel = NotificationChannel("trans", "Translate", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "trans")
            .setContentTitle("GameLingo AI")
            .setContentText("ระบบแปลหน้าจอพร้อมใช้งาน")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build())

        setupViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("CODE", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
        }
        if (code != 0 && data != null) {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(code, data)
            val dm = resources.displayMetrics
            reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
            projection?.createVirtualDisplay("Capture", dm.widthPixels, dm.heightPixels, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
        }
        return START_STICKY
    }

    private fun setupViews() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        
        // 1. ปุ่มลูกแก้วลอย (Native View ที่เร็วและไม่กินแรม)
        val p = WindowManager.LayoutParams(160, 160, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60; y = 350
        }
        val btn = TextView(this).apply {
            text = "แปล"
            textSize = 16f
            setTextColor(android.graphics.Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.CYAN)
            setOnClickListener { doTranslate() }
        }
        bubble = btn
        wm.addView(bubble, p)

        // 2. หน้าต่าง HUD แสดงคำแปล
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, 
            WindowManager.LayoutParams.WRAP_CONTENT, 
            type, 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, 
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 100
        }

        val hudCard = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#EE12131A"))
            setPadding(32, 24, 32, 24)
        }
        hudTextView = TextView(this).apply {
            text = "แตะปุ่ม 'แปล' เพื่อตรวจจับข้อความในเกม"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
        }
        hudCard.addView(hudTextView)
        hudCard.setOnClickListener { hudCard.visibility = View.GONE }
        hud = hudCard
        wm.addView(hud, hudParams)
    }

    private fun doTranslate() {
        hudTextView?.text = "กำลังอ่านข้อความบนหน้าจอ..."
        hud?.visibility = View.VISIBLE
        scope.launch {
            try {
                val img = reader?.acquireLatestImage()
                if (img != null) {
                    val plane = img.planes[0]
                    val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    img.close()

                    recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { res ->
                        val result = res.textBlocks.joinToString("\n") { it.text }
                        hudTextView?.text = if (result.isNotBlank()) "ตรวจพบข้อความ:\n$result" else "ไม่พบตัวอักษรภาษาอังกฤษบนจอ"
                    }.addOnFailureListener {
                        hudTextView?.text = "OCR ล้มเหลว: ${it.message}"
                    }
                } else {
                    hudTextView?.text = "ไม่สามารถบันทึกภาพหน้าจอได้ (อาจติด DRM)"
                }
            } catch (e: Exception) {
                hudTextView?.text = "เกิดข้อผิดพลาด: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        bubble?.let { wm.removeView(it) }
        hud?.let { wm.removeView(it) }
        reader?.close()
        projection?.stop()
    }
}
