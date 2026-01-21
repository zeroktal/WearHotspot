package com.example.wearhotspot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var isProxyRunning = false
    private var cellularNetwork: Network? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        statusText = TextView(this).apply { text = "Ready.\nConnect Watch to\nPhone Wi-Fi first."; textSize = 14f }
        val btnStart = Button(this).apply { text = "START PROXY" }
        val btnStop = Button(this).apply { text = "STOP" }

        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)

        btnStart.setOnClickListener {
            // Permission Check
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                return@setOnClickListener
            }
            startProxy()
        }
        
        btnStop.setOnClickListener {
            isProxyRunning = false
            statusText.text = "Stopped"
        }
    }

    private fun startProxy() {
        if (isProxyRunning) return
        isProxyRunning = true
        statusText.text = "Requesting LTE..."

        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            // This is the line that was likely crashing
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cellularNetwork = network
                    val wifiIp = getWifiIpAddress()
                    
                    runOnUiThread {
                        statusText.text = "PROXY ACTIVE!\n\n1. On Phone, go to Wi-Fi Settings\n2. Set Proxy to Manual\nHost: $wifiIp\nPort: 8080"
                    }
                    startServer()
                }
                
                override fun onLost(network: Network) {
                    cellularNetwork = null
                    runOnUiThread { statusText.text = "Lost LTE Connection!" }
                }

                override fun onUnavailable() {
                    runOnUiThread { statusText.text = "LTE Unavailable.\nCheck Data Plan." }
                }
            })
        } catch (e: Exception) {
            // CRASH CAUGHT HERE
            statusText.text = "Startup Error:\n${e.message}"
            isProxyRunning = false
        }
    }
    
    private fun getWifiIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.name.contains("wlan")) {
                    val enumIpAddr = intf.inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress ?: "?"
                        }
                    }
                }
            }
        } catch (ex: Exception) { return "Error: ${ex.message}" }
        return "No Wi-Fi IP Found"
    }

    private fun startServer() {
        Thread {
            try {
                val server = ServerSocket(8080)
                while (isProxyRunning) {
                    val client = server.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                isProxyRunning = false
                runOnUiThread { statusText.text = "Server Error:\n${e.message}" }
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        val network = cellularNetwork ?: return

        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { client.close(); return }
            
            val method = parts[0]
            val url = parts[1]
            
            var host = ""
            var port = 80
            
            if (method == "CONNECT") {
                val target = url.split(":")
                host = target[0]
                port = target.getOrElse(1) { "443" }.toInt()
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
            } else {
                val uri = URI(url)
                host = uri.host ?: url
                port = if(uri.port != -1) uri.port else 80
            }

            // Route via LTE
            val remote = network.socketFactory.createSocket(host, port)
            
            if (method != "CONNECT") {
                remote.getOutputStream().write("$requestLine\r\n".toByteArray())
            }
            
            val t1 = Thread { pipe(input, remote.getOutputStream()) }
            val t2 = Thread { pipe(remote.getInputStream(), output) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            
            remote.close()
            client.close()
        } catch (e: Exception) {
            try { client.close() } catch (ignore: Exception) {}
        }
    }
    
    private fun pipe(ins: InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        var read: Int
        try {
            while (ins.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                out.flush()
            }
        } catch (e: Exception) {}
    }
}
