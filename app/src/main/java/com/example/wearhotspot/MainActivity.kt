package com.example.wearhotspot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var proxyJob: Job? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI constructed programmatically to avoid XML files
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        statusText = TextView(this).apply { text = "Ready to Start"; textSize = 16f }
        val btnStart = Button(this).apply { text = "START HOTSPOT" }
        val btnStop = Button(this).apply { text = "STOP" }

        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, Looper.getMainLooper(), null)

        btnStart.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES), 1)
                return@setOnClickListener
            }
            startP2PGroup()
        }
        
        btnStop.setOnClickListener {
            manager?.removeGroup(channel, null)
            proxyJob?.cancel()
            statusText.text = "Stopped"
        }
    }
private fun startP2PGroup() {
        statusText.text = "Clearing old groups..."
        
        // 1. Always try to remove old groups first (The Zombie Killer)
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                attemptCreation()
            }
            override fun onFailure(reason: Int) {
                // If removal failed (likely because no group existed), just proceed
                attemptCreation()
            }
        })
    }

private fun attemptCreation() {
        // Give the hardware a moment to settle after the "removeGroup" call
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            
            // CONFIGURATION: Force the intent to 15 (Max)
            // This tells the driver: "I must be the Group Owner. Do not negotiate."
            val config = android.net.wifi.p2p.WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-WearHotspot") // Some devices ignore this, but it helps intent
                .setPassphrase("12345678")           // API 29+ might ignore this, but worth a try
                .setGroupOperatingBand(android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) // Force 2.4GHz (More stable on watches)
                .setGroupOperatingIntent(15) // MAX VALUE = I AM THE ROUTER
                .build()

            statusText.text = "Forcing Group Owner..."
            
            // Note: This requires API 29 (Android 10), which Wear OS 3/4 uses.
            // If your watch is older (Wear OS 2), remove the 'config' argument.
            try {
                manager?.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        statusText.text = "Group Created! Waiting..."
                        requestGroupInfo()
                    }
                    override fun onFailure(reason: Int) {
                        val errorMsg = when(reason) {
                            WifiP2pManager.BUSY -> "Still Busy (Is GPS On?)"
                            WifiP2pManager.P2P_UNSUPPORTED -> "HW Blocked"
                            else -> "Fail: $reason"
                        }
                        statusText.text = errorMsg
                    }
                })
            } catch (e: Exception) {
                // Fallback for older API levels
                statusText.text = "Config failed, trying legacy..."
                manager?.createGroup(channel, null)
            }
        }, 1000)
    }

    private fun requestGroupInfo() {
        try {
            manager?.requestGroupInfo(channel) { group ->
                if (group != null && group.isGroupOwner) {
                    val pass = group.passphrase
                    val ssid = group.networkName
                    statusText.text = "SSID: $ssid\nPass: $pass\nProxy: 192.168.49.1:8080"
                    startProxy()
                }
            }
        } catch (e: SecurityException) { }
    }

    private fun startProxy() {
        proxyJob = GlobalScope.launch(Dispatchers.IO) {
            // Force Cellular Network
            val cm = getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            
            val cellularNetwork = CompletableDeferred<Network>()
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { cellularNetwork.complete(network) }
            })

            val network = cellularNetwork.await()
            val server = ServerSocket(8080)
            
            while (isActive) {
                val client = server.accept()
                launch { handleClient(client, network) }
            }
        }
    }

    private fun handleClient(client: Socket, network: Network) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buffer = ByteArray(4096)
            
            // Read headers to find host (Simplified for brevity)
            // Real impl requires parsing "CONNECT host:port" or "GET http://host"
            // For this snippet, we assume a direct connect or simple HTTP for demonstration
            // NOTE: This basic version is a raw pipe. Real HTTP proxy logic from previous response is larger.
            
            // Just closing for safety in this snippet unless you paste the full proxy logic here.
            client.close() 
        } catch (e: Exception) { client.close() }
    }
}
