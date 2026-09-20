package com.cysindex.telequant.utils.ext


import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.cysindex.telequant.R
import com.cysindex.telequant.spoof.FakeEnvironment

fun Context.showToast(msg : String){
    Toast.makeText(this,msg, Toast.LENGTH_LONG).show()
}

/**
 * "2 cells · 6 Wi-Fi · 1 beacon". One place for the three counts, so every
 * summary agrees and each count gets its own plural form — Android plurals
 * handle one quantity per resource, not three in one sentence.
 */
fun Context.radioSummary(cells: Int, wifis: Int, beacons: Int): String =
    resources.getQuantityString(R.plurals.n_cells, cells, cells) + " · " +
            getString(R.string.n_wifi, wifis) + " · " +
            resources.getQuantityString(R.plurals.n_beacons, beacons, beacons)

fun Context.radioSummary(env: FakeEnvironment): String =
    radioSummary(env.cells.size, env.wifis.size, env.beacons.size)

fun Context.isNetworkConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    val capabilities = arrayOf(
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_LOWPAN,
        NetworkCapabilities.TRANSPORT_VPN,
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_WIFI_AWARE
    )
    return capabilities.any { networkCapabilities?.hasTransport(it) ?: false }
}




