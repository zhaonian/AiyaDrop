package io.zluan.aiyadrop

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.zluan.aiyadrop.ui.theme.AiyaDropTheme
import java.io.File
import java.net.Inet4Address
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

    @Composable
    private fun ServerScaffold() {
        var started by remember { mutableStateOf(false) }
        var ssid by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var urlHost by remember { mutableStateOf("192.168.43.1") }
        var isWorking by remember { mutableStateOf(false) }
        var workingText by remember { mutableStateOf("") }
        val port = 8765
        val dir = File(cacheDir, "aiyadrop").apply { mkdirs() }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            val granted = map.values.all { it }
            if (granted) {
                startLocalOnlyHotspot { s, p ->
                    ssid = s
                    password = p
                    findHotspotIpAddress()?.let { urlHost = it }
                    startServerIfNeeded(dir, port)
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
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .size(height = 8.dp, width = 0.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp))
                    Text(workingText.ifBlank { "Working…" })
                }
            } else if (started) {
                androidx.compose.foundation.layout.Column(
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
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                    Button(onClick = {
                        isWorking = true
                        workingText = "Stopping server…"
                        stopHotspotAndServer()
                        started = false
                        ssid = ""; password = ""
                        isWorking = false
                    }) {
                        Text("Stop server")
                    }
                }
            } else {
                androidx.compose.foundation.layout.Column(
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
                            }, dir = dir, port = port)
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

    private fun startServerIfNeeded(dir: File, port: Int) {
        if (webServer == null) {
            webServer = LocalWebServer(port, dir).also { it.start() }
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

    private fun startHotspotAndServer(onHotspot: ((String, String) -> Unit)? = null, dir: File = File(cacheDir, "aiyadrop").apply { mkdirs() }, port: Int = 8765) {
        startLocalOnlyHotspot { s, p ->
            onHotspot?.invoke(s, p)
            startServerIfNeeded(dir, port)
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