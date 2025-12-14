package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getDeviceIpAddress(context: Context): String? {
        try {
            Log.d(TAG, "Getting device IP address...")

            // Try method 1: NetworkInterface (more reliable)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        Log.d(TAG, "Found IP via NetworkInterface: $ip")
                        return ip
                    }
                }
            }

            // Try method 2: WifiManager (fallback)
            Log.d(TAG, "Trying WifiManager fallback...")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            if (ipAddress != 0) {
                val ip = String.format("%d.%d.%d.%d",
                    (ipAddress and 0xff),
                    (ipAddress shr 8 and 0xff),
                    (ipAddress shr 16 and 0xff),
                    (ipAddress shr 24 and 0xff)
                )
                Log.d(TAG, "Found IP via WifiManager: $ip")
                return ip
            }

            Log.w(TAG, "Could not get IP address from any method")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device IP address", e)
            return null
        }
    }
}
