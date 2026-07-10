package com.smarthealth.sync

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.google.android.gms.wearable.MessageClient
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.camera.view.PreviewView
import android.view.ViewGroup
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

class MainActivity : AppCompatActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private var currentSteps by mutableStateOf(7350)
    private var pressure by mutableStateOf<Float?>(1013f)
    private var temperature by mutableStateOf<Float?>(36.5f)
    private var heartRate by mutableStateOf<Float?>(86f)
    private var heartBeat by mutableStateOf<Float?>(72f)
    private var battery by mutableStateOf<Int?>(77)
    private var humidity by mutableStateOf<Float?>(45f)
    private var selectedTab by mutableStateOf("Inicio")
    
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var photoFile: File? = null
    private var showCamera by mutableStateOf(false)
    private var photosUpdated by mutableStateOf(0)
    private val CHANNEL_ID = "SmartHealthNotifications"
    private var batteryHighNotificationSent = false
    private var batteryLowNotificationSent = false

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                sendTextToWatch(spokenText)
                Toast.makeText(this, "Enviando: $spokenText", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "main") {
                composable("main") {
                    MainScreen(
                        currentSteps, temperature, heartRate, heartBeat, battery, humidity, pressure,
                        showCamera, photosUpdated, selectedTab, { selectedTab = it },
                        { showCamera = true }, { showCamera = false }, { takePhoto() },
                        { navController.navigate("gallery") }, { startVoiceRecognition() }
                    )
                }
                composable("gallery") {
                    PhotoGalleryScreen(this@MainActivity, { navController.popBackStack() }, { photosUpdated++ })
                }
            }
        }
        dataClient.addListener(this)
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/steps") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                runOnUiThread {
                    currentSteps = dataMap.getInt("steps")
                    temperature = dataMap.getFloat("temperature")
                    heartRate = dataMap.getFloat("heartRate")
                    heartBeat = dataMap.getFloat("heartBeat")
                    battery = dataMap.getInt("battery")
                    humidity = dataMap.getFloat("humidity")
                    pressure = dataMap.getFloat("pressure")
                    
                    if (battery != null) {
                        if (battery!! <= 20 && !batteryLowNotificationSent) {
                            showBatteryNotification("Batería baja", "Nivel: $battery%", 1)
                            batteryLowNotificationSent = true; batteryHighNotificationSent = false
                        } else if (battery!! >= 90 && !batteryHighNotificationSent) {
                            showBatteryNotification("Batería alta", "Carga óptima: $battery%", 3)
                            batteryHighNotificationSent = true; batteryLowNotificationSent = false
                        }
                    }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/take_photo" -> runOnUiThread { showCamera = true; sendPhotoTakenConfirmation() }
            "/capture_photo" -> runOnUiThread { takePhoto(); showCamera = false }
        }
    }

    fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            imageCapture = ImageCapture.Builder().build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            preview.setSurfaceProvider(previewView.surfaceProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        photoFile = File(getExternalFilesDir(null), "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile!!).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                sendPhotoToWatch(); sendPhotoCapturedConfirmation(); photosUpdated++
            }
            override fun onError(exception: ImageCaptureException) {}
        })
    }

    private fun sendPhotoToWatch() {
        photoFile?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val asset = Asset.createFromBytes(stream.toByteArray())
            val request = PutDataMapRequest.create("/photo_image").apply { dataMap.putAsset("photo", asset) }.asPutDataRequest()
            CoroutineScope(Dispatchers.IO).launch { try { dataClient.putDataItem(request).await() } catch (e: Exception) {} }
        }
    }

    private fun sendPhotoTakenConfirmation() {
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { messageClient.sendMessage(it.id, "/photo_taken", null) }
            } catch (e: Exception) {}
        }
    }

    private fun sendPhotoCapturedConfirmation() {
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { messageClient.sendMessage(it.id, "/photo_captured", null) }
            } catch (e: Exception) {}
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Hable al teléfono...")
        }
        try { speechLauncher.launch(intent) } catch (e: Exception) {}
    }

    private fun sendTextToWatch(text: String) {
        val messageClient = Wearable.getMessageClient(this)
        val nodeClient = Wearable.getNodeClient(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    nodes.forEach { messageClient.sendMessage(it.id, "/stt_text", text.toByteArray()).await() }
                } else {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Vincule su reloj mediante QR en Android Studio", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SmartHealth Sync", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showBatteryNotification(title: String, message: String, id: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_idle_low_battery).setContentTitle(title).setContentText(message).setAutoCancel(true)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(id, builder.build())
        }
    }
}

@Composable
fun MainScreen(steps: Int, temp: Float?, hr: Float?, hb: Float?, bat: Int?, hum: Float?, press: Float?, showCam: Boolean, photosUp: Int, tab: String, onTab: (String) -> Unit, onCam: () -> Unit, onOffCam: () -> Unit, onCap: () -> Unit, onGal: () -> Unit, onSTT: () -> Unit) {
    if (showCam) { CameraPreviewLayout(onOffCam, onCap) }
    else {
        Scaffold(containerColor = Color(0xFF0D0D17), bottomBar = { BottomNavBar(tab, onTab) }) { p ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(p).padding(horizontal = 20.dp)) {
                item { WelcomeHeader() }
                if (tab == "Inicio") {
                    item { StepsCard(steps) }
                    item { HealthSection(tab, temp, hr, hb, bat, hum, press) }
                } else {
                    item { HealthSection(tab, temp, hr, hb, bat, hum, press) }
                }
                item { QuickActions(onCam, onGal, onSTT, photosUp) }
                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
fun WelcomeHeader() {
    Column(modifier = Modifier.padding(vertical = 24.dp)) {
        Text("¡Hola, Paulina! 👋", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Bienvenida a SmartHealth Sync", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
fun StepsCard(steps: Int) {
    Card(modifier = Modifier.fillMaxWidth().height(180.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E))) {
        Row(modifier = Modifier.padding(20.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pasos hoy", color = Color.Gray, fontSize = 14.sp)
                Text(String.format("%,d", steps), color = Color(0xFF4CC9F0), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(progress = (steps / 10000f).coerceAtMost(1f), modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Color(0xFF4CC9F0), trackColor = Color(0xFF2D2D44))
            }
            Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(80.dp)) {
                    drawArc(Color(0xFF2D2D44), 0f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(Color(0xFF7209B7), -90f, (steps / 10000f) * 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Icon(Icons.Rounded.DirectionsWalk, null, tint = Color(0xFF7209B7), modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun HealthSection(tab: String, temp: Float?, hr: Float?, hb: Float?, bat: Int?, hum: Float?, press: Float?) {
    Column(modifier = Modifier.padding(vertical = 24.dp)) {
        Text(if (tab == "Inicio") "Monitor General" else "Salud Cardíaca", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        val sensors = if (tab == "Inicio") {
            listOf(Triple(Icons.Rounded.BatteryStd, "Batería", "${bat ?: "--"}%"), Triple(Icons.Rounded.Favorite, "Latidos", "${hb?.toInt() ?: "--"}"), Triple(Icons.Rounded.WaterDrop, "Humedad", "${hum?.toInt() ?: "--"}%"), Triple(Icons.Rounded.Air, "Presión", "${press?.toInt() ?: "--"}"))
        } else {
            listOf(Triple(Icons.Rounded.Favorite, "Ritmo Cardíaco", "${hr?.toInt() ?: "--"} bpm"), Triple(Icons.Rounded.Favorite, "Latidos", "${hb?.toInt() ?: "--"}"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in sensors.indices step 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (j in 0..1) if (i + j < sensors.size) SensorSmallCard(sensors[i + j].first, sensors[i + j].second, sensors[i + j].third, Color(0xFF4CC9F0), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SensorSmallCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, accent: Color, modifier: Modifier) {
    Card(modifier = modifier.height(100.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text(label, color = Color.Gray, fontSize = 10.sp) }
            Spacer(modifier = Modifier.height(8.dp)); Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QuickActions(onCam: () -> Unit, onGal: () -> Unit, onSTT: () -> Unit, updated: Int) {
    Column {
        Text("Acciones rápidas", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QuickButton(Icons.Rounded.CameraAlt, Color(0xFF4CC9F0), onCam, Modifier.weight(1f))
            QuickButton(Icons.Rounded.PhotoLibrary, Color(0xFF7209B7), onGal, Modifier.weight(1f))
            QuickButton(Icons.Rounded.Mic, Color(0xFF4361EE), onSTT, Modifier.weight(1f))
        }
    }
}

@Composable
fun QuickButton(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.height(60.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C2E))) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun BottomNavBar(sel: String, onTab: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0D0D17)) {
        val items = listOf(Triple(Icons.Rounded.Home, "Inicio", Color(0xFF7209B7)), Triple(Icons.Rounded.MonitorHeart, "Salud", Color(0xFF7209B7)))
        items.forEach { (icon, label, color) ->
            NavigationBarItem(selected = label == sel, onClick = { onTab(label) }, icon = { Icon(icon, null, tint = if (label == sel) color else Color.Gray) }, label = { Text(label, fontSize = 10.sp, color = if (label == sel) color else Color.Gray) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent))
        }
    }
}

@Composable
fun CameraPreviewLayout(onClose: () -> Unit, onCap: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; (c as? MainActivity)?.startCamera(this) } }, modifier = Modifier.fillMaxSize())
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onClose, modifier = Modifier.size(60.dp).background(Color.DarkGray, CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White) }
            IconButton(onClick = onCap, modifier = Modifier.size(80.dp).background(Color.White, CircleShape)) { Icon(Icons.Default.CameraAlt, null, tint = Color.Black, modifier = Modifier.size(40.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGalleryScreen(activity: MainActivity, onBack: () -> Unit, onPhotoDel: () -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(listPhotoFiles(context)) }
    var selectedPhoto by remember { mutableStateOf<File?>(null) }
    var showDel by remember { mutableStateOf(false) }

    Scaffold(containerColor = Color(0xFF0D0D17), topBar = { TopAppBar(title = { Text("Galería", color = Color.White) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D17))) }) { p ->
        if (photos.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay fotos", color = Color.Gray) }
        else { LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = p, modifier = Modifier.padding(4.dp)) { items(photos) { file ->
            val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
            Box(modifier = Modifier.aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(12.dp)).clickable { selectedPhoto = file }) {
                bitmap?.let { Image(it, null, contentScale = ContentScale.Crop) }
                IconButton(onClick = { selectedPhoto = file; showDel = true }, modifier = Modifier.align(Alignment.TopEnd).size(30.dp).background(Color.Black.copy(0.5f), CircleShape)) { Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(16.dp)) }
            }
        } } }
    }
    if (showDel) AlertDialog(onDismissRequest = { showDel = false }, title = { Text("Borrar foto") }, text = { Text("¿Deseas eliminar esta captura?") }, confirmButton = { TextButton(onClick = { selectedPhoto?.delete(); photos = listPhotoFiles(context); showDel = false; onPhotoDel() }) { Text("Borrar", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showDel = false }) { Text("Cancelar") } })
}

fun listPhotoFiles(context: android.content.Context): List<File> {
    val dir = context.getExternalFilesDir(null)
    return dir?.listFiles { file -> file.name.startsWith("photo_") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
