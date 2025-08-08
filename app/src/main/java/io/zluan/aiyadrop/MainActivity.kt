package io.zluan.aiyadrop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.zluan.aiyadrop.ui.theme.AiyaDropTheme
import java.io.File
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var webServer: LocalWebServer? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    // no activity-level launcher; use compose rememberLauncherForActivityResult instead

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiyaDropTheme {
                ServerScaffold()
            }
        }
    }

    override fun onDestroy() {
        webServer?.stop()
        webServer = null
        hotspotReservation?.close()
        hotspotReservation = null
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun ServerScaffold() {
        var started by remember { mutableStateOf(false) }
        var ssid by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var urlHost by remember { mutableStateOf("192.168.1.113") }
        var isWorking by remember { mutableStateOf(false) }
        var workingText by remember { mutableStateOf("") }
        var connectedDevices by remember { mutableStateOf(setOf<ClientInfo>()) }
        var outboundText by remember { mutableStateOf("") }
        var inboundMessages by remember { mutableStateOf(listOf<String>()) }
        val port = 8765
        val dir = File(cacheDir, "aiyadrop").apply { mkdirs() }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            val granted = map.values.all { it }
            if (granted) {
                startLocalOnlyHotspot { s, p ->
                    ssid = s
                    password = p
                    findHotspotIpAddress()?.let { urlHost = it }
                    startServerIfNeeded(dir, port,
                        onNewClient = { info ->
                        this@MainActivity.runOnUiThread {
                            connectedDevices = connectedDevices + info
                        }
                        },
                        onClientMessage = { fromIp, text ->
                            this@MainActivity.runOnUiThread {
                                inboundMessages = inboundMessages + "$fromIp: $text"
                            }
                        }
                    )
                    started = true
                    isWorking = false
                }
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                isWorking = false
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webServer?.stop(); webServer = null
                hotspotReservation?.close(); hotspotReservation = null
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            if (isWorking) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ContainedLoadingIndicator(modifier = Modifier.size(72.dp))
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(workingText.ifBlank { "Working…" })
                }
            } else if (started) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Hotspot ready", style = MaterialTheme.typography.titleMedium)
                    Text("SSID: $ssid", style = MaterialTheme.typography.bodyMedium)
                    Text("Password: $password", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Open: http://$urlHost:$port",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val wifiQrText = remember(ssid, password) {
                        // WIFI QR code format
                        "WIFI:T:WPA;S:$ssid;P:$password;;"
                    }
                    val qrBitmap = remember(wifiQrText) { generateQrCodeBitmap(wifiQrText, 256) }
                    val linkUrl = remember(urlHost) { "http://$urlHost:$port" }
                    val linkQrBitmap = remember(linkUrl) { generateQrCodeBitmap(linkUrl, 256) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Wi‑Fi QR",
                                    modifier = Modifier.size(196.dp)
                                )
                            }
                            Text("Wi‑Fi QR")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (linkQrBitmap != null) {
                                Image(
                                    bitmap = linkQrBitmap.asImageBitmap(),
                                    contentDescription = "Open link QR",
                                    modifier = Modifier.size(196.dp)
                                )
                            }
                            Text("Open link QR")
                        }
                    }
                    Text("Send message", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = outboundText,
                        onValueChange = { outboundText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message to send") }
                    )
                    Button(onClick = {
                        for (c in connectedDevices) {
                            webServer?.sendMessageTo(c.ip, outboundText)
                        }
                    }, enabled = connectedDevices.isNotEmpty() && outboundText.isNotBlank()) { Text("Send to all connected") }

                    if (connectedDevices.isNotEmpty()) {
                        Text("Connected devices", style = MaterialTheme.typography.titleSmall)
                        for (c in connectedDevices) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${c.ip} ${c.userAgent ?: ""}", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = {
                                    webServer?.sendMessageTo(c.ip, outboundText)
                                }, enabled = outboundText.isNotBlank()) { Text("Send") }
                            }
                        }
                    }
                    Text("Messages from devices", style = MaterialTheme.typography.titleSmall)
                    for (m in inboundMessages) {
                        Text(m, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(onClick = {
                        isWorking = true
                        workingText = "Stopping server…"
                        stopHotspotAndServer()
                        started = false
                        ssid = ""; password = ""
                        connectedDevices = emptySet()
                        isWorking = false
                    }) {
                        Text("Stop server")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Button(onClick = {
                        val perms = buildPermissions()
                        if (hasAllPermissions(perms)) {
                            isWorking = true
                            workingText = "Starting hotspot and server…"
                            startHotspotAndServer(onHotspot = { s, p ->
                                ssid = s; password = p; started = true
                                isWorking = false
                            },
                                dir = dir,
                                port = port,
                                onNewClient = { info ->
                                    this@MainActivity.runOnUiThread {
                                        connectedDevices = connectedDevices + info
                                    }
                                },
                                onClientMessage = { fromIp, text ->
                                    this@MainActivity.runOnUiThread {
                                        inboundMessages = inboundMessages + "$fromIp: $text"
                                    }
                                }
                            )
                        } else {
                            isWorking = true
                            workingText = "Starting hotspot and server…"
                            permLauncher.launch(perms)
                        }
                    }) {
                        Text("Start server")
                    }
                }
            }
        }
    }

    private fun startServerIfNeeded(
        dir: File,
        port: Int,
        onNewClient: (ClientInfo) -> Unit = {},
        onClientMessage: (String, String) -> Unit = { _, _ -> }
    ) {
        if (webServer == null) {
            val html = try {
                assets.open("index.html").bufferedReader().use { it.readText() }
            } catch (_: Throwable) { null }
            webServer = LocalWebServer(port, dir, onNewClient, onClientMessage, html).also { it.start() }
        }
    }

    private fun buildPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(perms: Array<String>): Boolean =
        perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startHotspotAndServer(
        onHotspot: ((String, String) -> Unit)? = null,
        dir: File = File(cacheDir, "aiyadrop").apply { mkdirs() },
        port: Int = 8765,
        onNewClient: (ClientInfo) -> Unit = {},
        onClientMessage: (String, String) -> Unit = { _, _ -> }
    ) {
        startLocalOnlyHotspot { s, p ->
            onHotspot?.invoke(s, p)
            startServerIfNeeded(dir, port, onNewClient, onClientMessage)
        }
    }

    private fun stopHotspotAndServer() {
        webServer?.stop(); webServer = null
        hotspotReservation?.close(); hotspotReservation = null
    }

    private fun findHotspotIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.") }
        } catch (_: Throwable) { null }
    }

    private fun startLocalOnlyHotspot(onReady: (String, String) -> Unit) {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    val config = reservation?.wifiConfiguration
                    val ssid = config?.SSID ?: "AiyaDrop"
                    val pass = config?.preSharedKey ?: ""
                    onReady(ssid, pass)
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    Toast.makeText(this@MainActivity, "Hotspot failed: $reason", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (t: Throwable) {
            Toast.makeText(this, "Hotspot error: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun generateQrCodeBitmap(text: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        createBitmap(width, height).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (_: Throwable) {
        null
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AiyaDropTheme {
        Greeting("Android")
    }
}