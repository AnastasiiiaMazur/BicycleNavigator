package com.bccle.navigator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Fast check for any active network
fun hasInternetConnection(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// Slower check to confirm actual internet access (via lightweight Google ping)
suspend fun hasRealInternetAccess(): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://clients3.google.com/generate_204")
            .build()

        val response = client.newCall(request).execute()
        response.code == 204
    } catch (e: Exception) {
        false
    }
}

// Combined check: network present AND actual internet access
suspend fun isInternetAvailable(context: Context): Boolean {
    if (!hasInternetConnection(context)) return false
    return hasRealInternetAccess()
}