package com.xelth.eckwms_movfast.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

object ConnectivityTester {
    private const val TAG = "ConnectivityTester"
    private const val TIMEOUT_MS = 2000L // 2 seconds timeout for each health check

    suspend fun findReachableUrl(urls: List<String>): String? = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            Log.w(TAG, "URL list is empty, cannot find reachable URL.")
            return@withContext null
        }

        Log.d(TAG, "Testing connectivity for URLs: $urls")

        val deferredResults = urls.map {
            async {
                withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        val url = URL("$it/health")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = TIMEOUT_MS.toInt()
                        connection.readTimeout = TIMEOUT_MS.toInt()

                        val responseCode = connection.responseCode
                        connection.disconnect()

                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            Log.i(TAG, "Successfully connected to $it")
                            it // Return the successful URL
                        } else {
                            Log.w(TAG, "Failed to connect to $it, status code: $responseCode")
                            null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception while testing $it: ${e.message}")
                        null
                    }
                }
            }
        }

        // This will await all checks, but we can iterate to find the first success
        for (deferred in deferredResults) {
            val result = deferred.await()
            if (result != null) {
                // Found a reachable URL, cancel others and return it
                deferredResults.forEach { if (!it.isCompleted) it.cancel() }
                return@withContext result
            }
        }

        Log.e(TAG, "No reachable URL found in the provided list.")
        return@withContext null // No URL was reachable
    }
}
