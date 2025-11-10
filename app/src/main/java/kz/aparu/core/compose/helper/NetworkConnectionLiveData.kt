package kz.aparu.core.compose.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData

/**
 * A lifecycle-aware LiveData that observes network connection status and quality.
 *
 * It emits a [NetworkStatus] enum to represent whether the connection is high-speed,
 * degraded (slow), or unavailable.
 *
 * @param context The application context to access the ConnectivityManager.
 */
class NetworkConnectionLiveData(context: Context) : LiveData<NetworkStatus>() {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            postValue(getNetworkStatus())
        }

        override fun onLost(network: Network) {
            postValue(NetworkStatus.Unavailable)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            postValue(getNetworkStatus())
        }
    }

    override fun onActive() {
        super.onActive()
        postValue(getNetworkStatus())
        registerCallbackSafely()
    }

    override fun onInactive() {
        super.onInactive()
        unregisterCallbackSafely()
    }

    private fun registerCallbackSafely() {
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE should normally be granted from the manifest, but if OEMs
            // block it we simply skip listening for further updates.
        }
    }

    private fun unregisterCallbackSafely() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was already unregistered or never registered; ignore.
        }
    }

    private fun getNetworkStatus(): NetworkStatus {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkStatus.Unavailable
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkStatus.Unavailable

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkStatus.Unavailable
        }

        val downstreamKbps = capabilities.linkDownstreamBandwidthKbps

        val isFast = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                isBandwidthFast(downstreamKbps, WIFI_FAST_THRESHOLD_KBPS)

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                isCellularFast(downstreamKbps)

            else -> isBandwidthFast(downstreamKbps, OTHER_FAST_THRESHOLD_KBPS)
        }

        return if (isFast) NetworkStatus.Available else NetworkStatus.Degraded
    }

    private fun isBandwidthFast(bandwidthKbps: Int, thresholdKbps: Int): Boolean {
        return bandwidthKbps == NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED || bandwidthKbps >= thresholdKbps
    }

    private fun isCellularFast(downstreamKbps: Int): Boolean {
        val networkType = safeDataNetworkType()

        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> false

            TelephonyManager.NETWORK_TYPE_UNKNOWN, null ->
                isBandwidthFast(downstreamKbps, CELLULAR_FAST_THRESHOLD_KBPS)

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> false

            else -> true
        }
    }

    private fun safeDataNetworkType(): Int? {
        val manager = telephonyManager ?: return null
        if (!hasAnyPhoneStatePermission()) return null

        return try {
            manager.dataNetworkType
        } catch (_: SecurityException) {
            null
        }
    }

    private fun hasAnyPhoneStatePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val basicPhoneStatePermission = PERMISSION_READ_BASIC_PHONE_STATE
            if (ContextCompat.checkSelfPermission(appContext, basicPhoneStatePermission) == PackageManager.PERMISSION_GRANTED) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val PERMISSION_READ_BASIC_PHONE_STATE = "android.permission.READ_BASIC_PHONE_STATE"
        private const val WIFI_FAST_THRESHOLD_KBPS = 550
        private const val OTHER_FAST_THRESHOLD_KBPS = 550
        private const val CELLULAR_FAST_THRESHOLD_KBPS = 200
    }
}


enum class NetworkStatus {
    /** The device is connected to a high-speed network (e.g., Wi-Fi, 4G, 5G). */
    Available,

    /** The device is connected, but the network is slow (e.g., 2G/EDGE). */
    Degraded,

    /** The device has no network connection. */
    Unavailable
}
