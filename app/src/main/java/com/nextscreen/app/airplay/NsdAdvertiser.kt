package com.nextscreen.app.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Registers `_airplay._tcp` and `_raop._tcp` mDNS records on the local network using Android NsdManager.
 */
class NsdAdvertiser(ctx: Context) {

    data class ServiceSpec(
        val name: String,
        val type: String,
        val port: Int,
        val txt: Map<String, String>,
    )

    private val nsdManager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = ArrayList<NsdManager.RegistrationListener>()

    @Synchronized
    fun register(services: List<ServiceSpec>) {
        unregister()
        for (s in services) {
            try {
                val info = NsdServiceInfo().apply {
                    serviceName = s.name
                    serviceType = s.type
                    port = s.port
                    for ((k, v) in s.txt) {
                        setAttribute(k, v)
                    }
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registered: NsdServiceInfo) {
                        Log.i(TAG, "${s.type} registered as '${registered.serviceName}' on port ${s.port}")
                    }

                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "${s.type} registration failed: $errorCode")
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "${s.type} unregistered")
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "${s.type} unregister failed: $errorCode")
                    }
                }
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners += listener
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register ${s.type}", e)
            }
        }
    }

    @Synchronized
    fun unregister() {
        for (l in listeners) {
            try {
                nsdManager.unregisterService(l)
            } catch (_: Exception) {
            }
        }
        listeners.clear()
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
    }
}
