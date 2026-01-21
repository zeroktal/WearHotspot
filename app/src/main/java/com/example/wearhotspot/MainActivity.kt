package com.example.wearhotspot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
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
        statusText = TextView(this).apply { text = "Connect Watch to\nPhone/PC Wi-Fi first!"; textSize = 14f }
        val btnStart = Button(this).apply { text = "START PROXY" }
        val btnStop = Button(this).apply { text = "STOP" }

        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)

        btnStart.setOnClickListener {
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
        statusText.text = "Finding LTE..."

        // 1. Find the LTE Network (The Exit Door)
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                
                // 2. Find MY IP Address (The Entrance Door)
                // We need the IP of the Wi-Fi interface so you know what to type in the Phone
                val wifiIp = getWifiIpAddress()
                
                runOnUiThread {
                    statusText.text = "Active!\nProxy IP: $wifiIp\nPort: 8080"
                }
                
                // 3. Start the Server
                startServer()
            }
            
            override fun onLost(network: Network) {
                cellularNetwork = null
                runOnUiThread { statusText.text = "Lost LTE Connection!" }
            }
        })
    }
    
    // Helper to find the Watch's Local Wi-Fi IP
    private fun getWifiIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                // We usually want 'wlan0'
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
        } catch (ex: Exception) { }
        return "Unknown IP"
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
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        val network = cellularNetwork
        if (network == null) {
            client.close()
            return
        }

        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // Basic HTTP Parsing to find target
            // Note: This is a simplified "Blind Pipe" for HTTPS (CONNECT) 
            // Real usage might need the header parsing logic from our earlier chats if HTTP fails.
            
            // For this test: We read the request, extract host, and pipe it.
            // Simplified: We assume the client sends a CONNECT request or standard HTTP
            // Reading the first line to peek at the host...
            // (Full implementation omitted for brevity, but this will work for basic tunnels if we just pipe)
             
            // -- CRITICAL SIMPLIFICATION --
            // Since parsing is complex in one file, we will implement a "Blind Forwarder"
            // You will need to type the target IP in your browser if this fails, 
            // OR use the full parsing logic if you want seamless browsing.
            
            // Let's use the 'network' to open a socket to the outside world.
            // WARNING: Without parsing the headers, we don't know WHERE the client wants to go.
            // I will restore the basic Header Parser here so it works for you.
            
            val buffer = ByteArray(4096)
            // We need to read the header to find the host
            // But we can't consume the stream easily without breaking it.
            // For now, let's just close to keep this buildable. 
            // ** You need the Parsing Logic from my previous reply for full internet **
            
            // RE-INSERTING BASIC PARSER:
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
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
                
                // Respond "OK" to client
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
            } else {
                // HTTP GET http://google.com/...
                val uri = java.net.URI(url)
                host = uri.host ?: url
                port = if(uri.port != -1) uri.port else 80
                // We don't write an OK response for HTTP, we just forward the request line
            }

            // Create Outgoing Socket on LTE
            val remote = network.socketFactory.createSocket(host, port)
            
            // If HTTP, forward the line we just read
            if (method != "CONNECT") {
                remote.getOutputStream().write("$requestLine\r\n".toByteArray())
            }
            
            // Pipe Data
            val t1 = Thread { pipe(input, remote.getOutputStream()) }
            val t2 = Thread { pipe(remote.getInputStream(), output) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            
        } catch (e: Exception) {
            try { client.close() } catch (ignore: Exception) {}
        }
    }
    
    private fun pipe(ins: InputStream, out: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            var read: Int
            while (ins.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                out.flush()
            }
        } catch (e: Exception) {}
    }
}
