package com.smarthealth.sync

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.smarthealth.sync.ui.theme.WearAppTheme
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.google.android.gms.wearable.MessageClient
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity(), SensorEventListener, MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private lateinit var sensorManager: SensorManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private val dataClient by lazy { Wearable.getDataClient(this) }
    
    private var steps by mutableStateOf(7350)
    private var temperature by mutableStateOf<Float?>(36.5f)
    private var heartRate by mutableStateOf<Float?>(86f)
    private var heartBeat by mutableStateOf<Float?>(72f)
    private var batteryLevel by mutableStateOf<Int?>(77)
    private var pressure by mutableStateOf<Float?>(1013f)
    private var lux by mutableStateOf<Float?>(320f)
    private var humidity by mutableStateOf<Float?>(45f)
    private var proximity by mutableStateOf<Float?>(12f)
    private var magneticField by mutableStateOf<Float?>(35f)
    
    private var cameraReady by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var lastVoiceMessage by mutableStateOf<String?>(null)
    
    private var notificationMessage by mutableStateOf<String?>(null)
    private var notificationType by mutableStateOf(NotificationType.INFO)
    private var showNotification by mutableStateOf(false)

    enum class NotificationType { SUCCESS, ERROR, INFO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            WearAppTheme {
                val navController = remember { mutableStateOf("main") }
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D17))) {
                    if (navController.value == "main") {
                        WearMainScreen(
                            steps = steps, temp = temperature, hr = heartRate, hb = heartBeat,
                            bat = batteryLevel, press = pressure, lux = lux, hum = humidity,
                            onCam = { sendTakePhotoMessage() },
                            onGal = { navController.value = "gallery" },
                            onVoice = { /* STT logic handled on mobile */ },
                            lastMsg = lastVoiceMessage, camReady = cameraReady, isProc = isProcessing
                        )
                    } else {
                        WearGalleryScreen(onBack = { navController.value = "main" })
                    }
                    
                    if (showNotification) {
                        ModernNotification(notificationMessage ?: "", notificationType) { showNotification = false }
                    }
                }
            }
        }
        checkAndRequestPermissions()
        getBatteryLevel()
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getDataClient(this).addListener(this)
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
        ActivityCompat.requestPermissions(this, permissions, 100)
        
        val sensors = listOf(
            Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_HEART_RATE, Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_PROXIMITY, Sensor.TYPE_MAGNETIC_FIELD
        )
        sensors.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        // Sensor de Latido
        sensorManager.getDefaultSensor(65572)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> steps = event.values[0].toInt()
            Sensor.TYPE_HEART_RATE -> heartRate = event.values[0]
            Sensor.TYPE_AMBIENT_TEMPERATURE -> temperature = event.values[0]
            Sensor.TYPE_PRESSURE -> pressure = event.values[0]
            Sensor.TYPE_LIGHT -> lux = event.values[0]
            Sensor.TYPE_RELATIVE_HUMIDITY -> humidity = event.values[0]
            Sensor.TYPE_PROXIMITY -> proximity = event.values[0]
            Sensor.TYPE_MAGNETIC_FIELD -> magneticField = event.values[0]
            65572 -> heartBeat = event.values[0]
        }
        syncDataToMobile()
    }

    private fun syncDataToMobile() {
        scope.launch {
            try {
                val request = PutDataMapRequest.create("/steps").apply {
                    dataMap.putInt("steps", steps)
                    temperature?.let { dataMap.putFloat("temperature", it) }
                    heartRate?.let { dataMap.putFloat("heartRate", it) }
                    heartBeat?.let { dataMap.putFloat("heartBeat", it) }
                    batteryLevel?.let { dataMap.putInt("battery", it) }
                    humidity?.let { dataMap.putFloat("humidity", it) }
                    pressure?.let { dataMap.putFloat("pressure", it) }
                }.asPutDataRequest()
                dataClient.putDataItem(request)
            } catch (e: Exception) {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun getBatteryLevel() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    }

    fun sendTakePhotoMessage() {
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    val path = if (!cameraReady) "/take_photo" else "/capture_photo"
                    if (cameraReady) isProcessing = true
                    messageClient.sendMessage(nodes[0].id, path, null).await()
                } else {
                    runOnUiThread { showNotification("Conecte celular", NotificationType.ERROR) }
                }
            } catch (e: Exception) {}
        }
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        runOnUiThread {
            when (event.path) {
                "/photo_taken" -> { cameraReady = true; showNotification("Cámara lista") }
                "/photo_captured" -> { isProcessing = false; cameraReady = false; showNotification("Capturada exitosamente", NotificationType.SUCCESS) }
                "/stt_text" -> { lastVoiceMessage = String(event.data); showNotification(String(event.data), NotificationType.INFO) }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/photo_image") {
                val asset = DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset("photo")
                asset?.let {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val fd = Wearable.getDataClient(this@MainActivity).getFdForAsset(it).await()
                            fd.inputStream.use { input ->
                                val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                                file.outputStream().use { output -> input.copyTo(output) }
                                runOnUiThread { showNotification("Foto recibida", NotificationType.SUCCESS) }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
    }

    private fun showNotification(message: String, type: NotificationType = NotificationType.INFO) {
        notificationMessage = message; notificationType = type; showNotification = true
    }
}

@Composable
fun WearMainScreen(
    steps: Int, temp: Float?, hr: Float?, hb: Float?, bat: Int?, press: Float?, lux: Float?, hum: Float?,
    onCam: () -> Unit, onGal: () -> Unit, onVoice: () -> Unit, lastMsg: String?, camReady: Boolean, isProc: Boolean
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 20.dp)
    ) {
        // Header
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.Start) {
                Text("¡Hola, Alberto! 👋", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Bienvenida a SmartHealth Sync", color = Color.Gray, fontSize = 8.sp)
            }
        }

        // Steps Card (Advanced)
        item {
            val animatedProgress by animateFloatAsState(
                targetValue = (steps / 10000f).coerceIn(0f, 1f),
                animationSpec = tween(1200, easing = FastOutSlowInEasing),
                label = "steps"
            )
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .border(1.dp, Brush.linearGradient(listOf(Color(0xFF4CC9F0).copy(0.4f), Color(0xFF7209B7).copy(0.4f))), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                backgroundPainter = CardDefaults.cardBackgroundPainter(
                    startBackgroundColor = Color(0xFF1C1C2E),
                    endBackgroundColor = Color(0xFF0D0D17)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PASOS", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            String.format("%,d", steps),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2D2D44))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress)
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF4CC9F0), Color(0xFF4361EE))))
                            )
                        }
                    }
                    Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.size(48.dp)) {
                            drawArc(
                                Color(0xFF2D2D44),
                                0f,
                                360f,
                                false,
                                style = Stroke(6.dp.toPx())
                            )
                            drawArc(
                                Color(0xFF7209B7),
                                -90f,
                                animatedProgress * 360f,
                                false,
                                style = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Icon(
                            Icons.Rounded.DirectionsWalk,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        item { Text("Monitor General", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).fillMaxWidth()) }

        // Monitor General Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SensorSmallCard(Icons.Rounded.BatteryStd, "Batería", "${bat ?: "--"}%", Color(0xFF4361EE), Modifier.weight(1f))
                    SensorSmallCard(Icons.Rounded.Favorite, "Latidos", "${hb?.toInt() ?: "--"}", Color(0xFFF72585), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SensorSmallCard(Icons.Rounded.WaterDrop, "Humedad", "${hum?.toInt() ?: "--"}%", Color(0xFF4CC9F0), Modifier.weight(1f))
                    SensorSmallCard(Icons.Rounded.Air, "Presión", "${press?.toInt() ?: "--"}", Color(0xFF4CC9F0), Modifier.weight(1f))
                }
            }
        }

        item { Text("Acciones rápidas", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).fillMaxWidth()) }

        // Quick Actions Row
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickActionIcon(Icons.Rounded.CameraAlt, Color(0xFF4CC9F0), onCam)
                QuickActionIcon(Icons.Rounded.PhotoLibrary, Color(0xFF7209B7), onGal)
                QuickActionIcon(Icons.Rounded.Mic, Color(0xFF4361EE), onVoice)
            }
        }

        // Voice Message (Display Received STT)
        if (lastMsg != null) {
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), backgroundPainter = CardDefaults.cardBackgroundPainter(startBackgroundColor = Color(0xFF003566))) {
                    Text(lastMsg, fontSize = 9.sp, color = Color.White)
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun SensorSmallCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, accent: Color, modifier: Modifier) {
    val isHeart = label.contains("Latidos")
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by if (isHeart) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Card(
        onClick = {},
        modifier = modifier
            .height(65.dp)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(startBackgroundColor = Color(0xFF1C1C2E).copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, 
                    null, 
                    tint = accent, 
                    modifier = Modifier.size(15.dp).graphicsLayer(scaleX = scale, scaleY = scale)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, color = Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun QuickActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C1C2E))
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun WearGalleryScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val photos = remember { File(context.cacheDir.path).listFiles { f -> f.name.startsWith("photo_") }?.toList() ?: emptyList() }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D17)), horizontalAlignment = Alignment.CenterHorizontally) {
        item { CompactButton(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C1C2E))) { Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp)) } }
        items(photos) { AsyncImage(model = it.absolutePath, contentDescription = null, modifier = Modifier.size(110.dp).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C2E)), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun ModernNotification(message: String, type: MainActivity.NotificationType, onDismiss: () -> Unit) {
    val color = when(type) { 
        MainActivity.NotificationType.SUCCESS -> Color(0xFF4CAF50)
        MainActivity.NotificationType.ERROR -> Color(0xFFF44336)
        else -> Color(0xFF4CC9F0) 
    }
    LaunchedEffect(Unit) { delay(3000); onDismiss() }
    Box(modifier = Modifier.fillMaxSize().padding(10.dp).zIndex(100f), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1C1C2E), RoundedCornerShape(12.dp))
                .border(1.dp, color, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Text(message, color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
