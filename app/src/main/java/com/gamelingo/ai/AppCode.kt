package com.gamelingo.ai

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.room.*
import androidx.savedstate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer

// 1. Database
@Entity(tableName = "history")
data class HistoryItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String, val translated: String)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(item: HistoryItem)
}

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): HistoryDao
}

// 2. Application
class GameLingoApp : Application() {
    lateinit var db: AppDb
    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDb::class.java, "game.db").build()
    }
}

// 3. UI Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isRunning by remember { mutableStateOf(false) }
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                if (res.resultCode == Activity.RESULT_OK && res.data != null) {
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
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(if (isRunning) "หยุดการทำงาน" else "เริ่มเปิดปุ่มแปลภาษาลอยหน้าจอ")
                    }
                }
            }
        }
    }
}

// 4. Overlay & Capture Service
class ScreenCaptureOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var hud: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val appViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = appViewModelStore

    private val detectedTextState = mutableStateOf("")
    private val isTranslating = mutableStateOf(false)

    override fun onBind(intent: Intent?) = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val channel = NotificationChannel("trans", "Translate", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "trans").setContentTitle("GameLingo").setContentText("พร้อมแปล").setSmallIcon(android.R.drawable.ic_menu_search).build())

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
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
        }

        val frame = FrameLayout(this)
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        frame.setViewTreeViewModelStoreOwner(this)

        frame.addView(ComposeView(this).apply {
            setContent {
                Box(Modifier.size(54.dp).background(Color(0xFF00E5FF), CircleShape).clickable { doTranslate() }, Alignment.Center) {
                    Icon(Icons.Default.Translate, null, tint = Color.Black)
                }
            }
        })
        bubble = frame
        wm.addView(bubble, p)

        val hudParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM
            y = 80
        }
        val hudFrame = FrameLayout(this)
        hudFrame.setViewTreeLifecycleOwner(this)
        hudFrame.setViewTreeSavedStateRegistryOwner(this)
        hudFrame.setViewTreeViewModelStoreOwner(this)
        hudFrame.addView(ComposeView(this).apply {
            setContent {
                val txt by remember { detectedTextState }
                val loading by remember { isTranslating }
                if (loading || txt.isNotEmpty()) {
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xEE12131A)), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("คำแปลภาษาไทย", color = Color(0xFF00E5FF), fontSize = 12.sp)
                                IconButton(onClick = { detectedTextState.value = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, null, tint = Color.White)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF00E5FF))
                            else Text(txt, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        })
        hud = hudFrame
        wm.addView(hud, hudParams)
    }

    private fun doTranslate() {
        isTranslating.value = true
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
                        detectedTextState.value = if (result.isNotBlank()) "ตรวจพบข้อความ:\n$result" else "ไม่พบตัวอักษรบนหน้าจอ"
                        isTranslating.value = false
                    }.addOnFailureListener {
                        detectedTextState.value = "OCR ผิดพลาด: ${it.message}"
                        isTranslating.value = false
                    }
                } else {
                    detectedTextState.value = "ไม่สามารถบันทึกภาพหน้าจอได้"
                    isTranslating.value = false
                }
            } catch (e: Exception) {
                detectedTextState.value = "เกิดข้อผิดพลาด: ${e.message}"
                isTranslating.value = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        appViewModelStore.clear()
        bubble?.let { wm.removeView(it) }
        hud?.let { wm.removeView(it) }
        reader?.close()
        projection?.stop()
    }
}
