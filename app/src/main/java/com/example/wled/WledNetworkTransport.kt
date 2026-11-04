package com.example.wled

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class WledLinkType(val label: String, val badgeText: String) {
    ETHERNET_WIRED("USB-C Ethernet (Wired)", "ETHERNET"),
    WIFI_BOUND("Wi-Fi Bound (No 5G Fallback)", "WI-FI LOCKED"),
    USB_SERIAL_OTG("USB-C Serial (115200 Baud)", "USB SERIAL"),
    SYSTEM_DEFAULT("System Default Routing", "DEFAULT"),
    OFFLINE("No Local Interface", "OFFLINE")
}

data class WledTransportState(
    val linkType: WledLinkType = WledLinkType.SYSTEM_DEFAULT,
    val interfaceName: String? = null,
    val localIpAddress: String? = null,
    val isBoundToLocalInterface: Boolean = false,
    val usbSerialDeviceName: String? = null,
    val lastPingLatencyMs: Long? = null,
    val lastPingSuccess: Boolean? = null,
    val activeHost: String? = null
)

/**
 * Manages physical transport selection and socket binding between the Android phone and the
 * bicycle's WLED controller across two real-world rider workflows:
 *
 * 1. **Phone Charged -> USB-C to Ethernet (or USB-C Serial)**:
 *    Automatically detects `NetworkCapabilities.TRANSPORT_ETHERNET` (when a USB-C to Ethernet
 *    adapter is plugged in) and binds OkHttp and UDP sockets directly to the wired interface.
 *    Also detects direct USB-C Serial microcontrollers (ESP32/ESP8266 CDC/CP210x/CH340) for
 *    optional `115200` baud JSON-over-Serial output.
 *
 * 2. **Phone Charging -> Hardened Wi-Fi (`TRANSPORT_WIFI`)**:
 *    When the USB-C port is occupied by a charger/power bank, binds OkHttp (`network.socketFactory`
 *    + `network::getAllByName`) and UDP port `11988` (`network.bindSocket`) directly to the
 *    active Wi-Fi `Network`. This prevents Android's `ConnectivityManager` from routing private
 *    WLED traffic (`4.3.2.1`, `192.168.x.x`, `prettylights.local`, `239.0.0.1`) out over 5G/LTE
 *    cellular when the bike's Wi-Fi network has no internet access.
 */
class WledNetworkTransport(private val context: Context?) {

    private val connectivityManager: ConnectivityManager? =
        context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val usbManager: UsbManager? =
        context?.getSystemService(Context.USB_SERVICE) as? UsbManager

    @Volatile
    var lockToLocalNetwork: Boolean = true
        set(value) {
            field = value
            refreshTransportState()
        }

    @Volatile
    var usbSerialFallbackEnabled: Boolean = true
        set(value) {
            field = value
            refreshTransportState()
        }

    private val _transportState = MutableStateFlow(WledTransportState())
    val transportState: StateFlow<WledTransportState> = _transportState.asStateFlow()

    private val sharedPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    // Cache last resolved IPv4 per hostname so transient mDNS hiccups on 2.4 GHz Wi-Fi don't stall brake updates
    private val resolvedHostCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .writeTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(sharedPool)
        .build()

    @Volatile
    private var cachedBoundNetwork: Network? = null

    @Volatile
    private var cachedBoundHttpClient: OkHttpClient? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshTransportState()
        }

        override fun onLost(network: Network) {
            refreshTransportState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshTransportState()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refreshTransportState()
        }
    }

    init {
        registerCallbacksSafely()
        refreshTransportState()
    }

    private fun registerCallbacksSafely() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.d(TAG, "NetworkCallback registration skipped: ${e.message}")
        }
    }

    /**
     * Scans active networks in priority order:
     * 1. `TRANSPORT_ETHERNET` (USB-C to Ethernet adapter)
     * 2. `TRANSPORT_WIFI` (Wi-Fi connection, even without internet validation)
     * 3. Direct USB-C OTG Serial device (`UsbManager`)
     */
    fun refreshTransportState(): WledTransportState {
        val cm = connectivityManager
        var ethernetNetwork: Network? = null
        var wifiNetwork: Network? = null

        if (cm != null) {
            try {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        ethernetNetwork = network
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiNetwork = network
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error inspecting active networks: ${e.message}")
            }
        }

        val usbSerialDevice = findWledUsbSerialDevice()
        val chosenNetwork: Network?
        val linkType: WledLinkType

        when {
            ethernetNetwork != null && lockToLocalNetwork -> {
                chosenNetwork = ethernetNetwork
                linkType = WledLinkType.ETHERNET_WIRED
            }
            wifiNetwork != null && lockToLocalNetwork -> {
                chosenNetwork = wifiNetwork
                linkType = WledLinkType.WIFI_BOUND
            }
            usbSerialDevice != null && usbSerialFallbackEnabled -> {
                chosenNetwork = ethernetNetwork ?: wifiNetwork
                linkType = WledLinkType.USB_SERIAL_OTG
            }
            ethernetNetwork != null || wifiNetwork != null -> {
                chosenNetwork = null
                linkType = WledLinkType.SYSTEM_DEFAULT
            }
            else -> {
                chosenNetwork = null
                linkType = if (cm == null) WledLinkType.SYSTEM_DEFAULT else WledLinkType.OFFLINE
            }
        }

        val linkProps = if (cm != null && (chosenNetwork != null || ethernetNetwork != null || wifiNetwork != null)) {
            try {
                cm.getLinkProperties(chosenNetwork ?: ethernetNetwork ?: wifiNetwork)
            } catch (e: Exception) {
                null
            }
        } else null

        val ifaceName = linkProps?.interfaceName
        val ipv4Address = linkProps?.linkAddresses
            ?.mapNotNull { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress

        val current = _transportState.value
        val updated = current.copy(
            linkType = linkType,
            interfaceName = ifaceName,
            localIpAddress = ipv4Address,
            isBoundToLocalInterface = chosenNetwork != null && lockToLocalNetwork,
            usbSerialDeviceName = usbSerialDevice?.productName ?: usbSerialDevice?.deviceName
        )
        _transportState.value = updated
        return updated
    }

    /**
     * Returns an OkHttpClient bound to the active USB-C Ethernet or Wi-Fi network when
     * `lockToLocalNetwork` is enabled (or null if no local network is available), or the default client otherwise.
     */
    fun getHttpClient(): OkHttpClient? {
        refreshTransportState()
        if (!lockToLocalNetwork) {
            return defaultHttpClient
        }

        val targetNetwork = findPreferredLocalNetwork() ?: return null
        val existingNet = cachedBoundNetwork
        val existingClient = cachedBoundHttpClient
        if (existingNet == targetNetwork && existingClient != null) {
            return existingClient
        }

        val boundClient = try {
            OkHttpClient.Builder()
                .connectTimeout(900, TimeUnit.MILLISECONDS)
                .writeTimeout(900, TimeUnit.MILLISECONDS)
                .readTimeout(900, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(sharedPool)
                .socketFactory(targetNetwork.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return try {
                            val resolved = targetNetwork.getAllByName(hostname).toList()
                            if (resolved.isNotEmpty()) {
                                resolvedHostCache[hostname.lowercase()] = resolved
                            }
                            resolved
                        } catch (e: Exception) {
                            resolvedHostCache[hostname.lowercase()] ?: Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
                .build()
        } catch (e: Exception) {
            Log.d(TAG, "Falling back to default OkHttpClient: ${e.message}")
            defaultHttpClient
        }

        cachedBoundNetwork = targetNetwork
        cachedBoundHttpClient = boundClient
        return boundClient
    }

    /**
     * Binds a UDP DatagramSocket (used by MusicService on port 11988) directly to the active
     * USB-C Ethernet (`TRANSPORT_ETHERNET`) or Wi-Fi (`TRANSPORT_WIFI`) network so 50 Hz
     * Audio-Reactive V2 packets never route over 5G/LTE cellular.
     */
    fun bindUdpSocket(socket: DatagramSocket): Boolean {
        refreshTransportState()
        if (!lockToLocalNetwork) return false
        val targetNetwork = findPreferredLocalNetwork() ?: return false
        return try {
            targetNetwork.bindSocket(socket)
            Log.d(TAG, "Bound UDP socket to local network: ${_transportState.value.linkType}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind UDP socket to local network: ${e.message}")
            false
        }
    }

    fun recordPingResult(host: String, success: Boolean, latencyMs: Long?) {
        _transportState.value = _transportState.value.copy(
            lastPingSuccess = success,
            lastPingLatencyMs = if (success) latencyMs else null,
            activeHost = host
        )
    }

    private fun findPreferredLocalNetwork(): Network? {
        val cm = connectivityManager ?: return null
        var ethernetNetwork: Network? = null
        var wifiNetwork: Network? = null
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethernetNetwork = network
                    break // Ethernet is highest priority
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetwork = network
                }
            }
        } catch (e: Exception) {
            return null
        }
        return ethernetNetwork ?: wifiNetwork
    }

    /**
     * Checks for connected USB-C Serial microcontrollers commonly running WLED
     * (Silicon Labs CP210x, QinHeng CH340/CH9102, FTDI, or Espressif native USB CDC).
     */
    private fun findWledUsbSerialDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return try {
            manager.deviceList?.values?.firstOrNull { device ->
                device.vendorId in KNOWN_WLED_USB_VIDS ||
                    device.deviceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                    device.deviceClass == UsbConstants.USB_CLASS_COMM
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attempts to transmit WLED JSON state payload over USB-C Serial (115200 8N1) if a
     * permission-granted USB CDC/UART microcontroller is connected.
     * Reference: https://kno.wled.ge/interfaces/json-api/ (JSON over Serial at 115200 baud).
     */
    fun trySendUsbSerialJson(jsonPayload: String): Boolean {
        if (!usbSerialFallbackEnabled) return false
        val manager = usbManager ?: return false
        val device = findWledUsbSerialDevice() ?: return false
        if (!manager.hasPermission(device)) return false

        val connection = manager.openDevice(device) ?: return false
        return try {
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        if (connection.claimInterface(usbInterface, true)) {
                            val bytes = (jsonPayload + "\n").toByteArray(Charsets.UTF_8)
                            val transferred = connection.bulkTransfer(endpoint, bytes, bytes.size, 250)
                            connection.releaseInterface(usbInterface)
                            if (transferred >= 0) {
                                return true
                            }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.d(TAG, "USB Serial transfer skipped: ${e.message}")
            false
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "WledNetworkTransport"

        // Silicon Labs CP210x (0x10C4), QinHeng CH340/CH9102 (0x1A86), FTDI (0x0403), Espressif USB (0x303A)
        private val KNOWN_WLED_USB_VIDS = setOf(0x10C4, 0x1A86, 0x0403, 0x303A)
    }
}
