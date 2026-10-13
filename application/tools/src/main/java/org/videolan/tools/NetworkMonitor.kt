package org.videolan.tools

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.NetworkInterface
import java.net.SocketException

class NetworkMonitor(private val context: Context) : DefaultLifecycleObserver {
    private var registered = false
    private val cm = context.getSystemService<ConnectivityManager>()!!
    val connectionFlow = MutableStateFlow(Connection(connected = false, mobile = true, vpn = false))
    val connected : Boolean
        get() = connectionFlow.value.connected
    val isLan : Boolean
        get() = context.canAccessLocalNetwork() && connectionFlow.value.run { connected && !mobile }
    val lanAllowed : Boolean
        get() = context.canAccessLocalNetwork() && connectionFlow.value.run { connected && (!mobile || vpn) }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                updateConnection()
            }
        }
    }

    init {
        register()
    }

    fun register() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        stop()
        lifecycle.removeObserver(this)
        if (context.canAccessLocalNetwork()) {
            lifecycle.addObserver(this)
        } else {
            connectionFlow.value = Connection(connected = false, mobile = true, vpn = false)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (registered) return
        registered = true
        val networkFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(receiver, networkFilter)
        updateConnection()
    }

    @SuppressLint("MissingPermission")
    private fun updateConnection() {
        val networkInfo = cm.activeNetworkInfo
        val isConnected = networkInfo != null && networkInfo.isConnected
        val isMobile = isConnected && networkInfo!!.type == ConnectivityManager.TYPE_MOBILE
        val isVPN = isConnected && updateVPNStatus()
        connectionFlow.value = Connection(isConnected, isMobile, isVPN)
    }

    override fun onStop(owner: LifecycleOwner) = stop()

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
        connectionFlow.value = Connection(connected = false, mobile = true, vpn = false)
    }

    protected fun finalize() {
        stop()
    }

    @SuppressLint("MissingPermission")
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun updateVPNStatus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (network in cm.allNetworks) {
                val nc = cm.getNetworkCapabilities(network) ?: return false
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
            }
            return false
        } else {
            try {
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    val name = networkInterface.displayName
                    if (name.startsWith("ppp") || name.startsWith("tun") || name.startsWith("tap"))
                        return true
                }
            } catch (ignored: SocketException) {}
            return false
        }
    }

    companion object : SingletonHolder<NetworkMonitor, Context>({ NetworkMonitor(it.applicationContext) })
}

class Connection(val connected: Boolean, val mobile: Boolean, val vpn: Boolean)
