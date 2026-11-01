package com.example.wled

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.config.WledLook
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramSocket

data class WledPreset(
    val id: Int,
    val name: String
)

// Models representing the WLED JSON State payload structure
data class WledSegment(
    val col: List<List<Int>>? = null,
    val fx: Int = 0,
    val fp: Int = 0,
    val sx: Int = 128,
    val ix: Int = 128
)

data class WledStatePayload(
    val on: Boolean = true,
    val bri: Int,
    val ps: Int? = null,
    val transition: Int? = null,
    val seg: List<WledSegment>? = null
)

class WledController(context: Context? = null) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val payloadAdapter = moshi.adapter(WledStatePayload::class.java)

    val networkTransport = WledNetworkTransport(context)
    val transportState: StateFlow<WledTransportState> = networkTransport.transportState

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateTransportConfig(lockToLocalNetwork: Boolean, usbSerialFallbackEnabled: Boolean) {
        networkTransport.lockToLocalNetwork = lockToLocalNetwork
        networkTransport.usbSerialFallbackEnabled = usbSerialFallbackEnabled
    }

    fun refreshTransport(): WledTransportState {
        return networkTransport.refreshTransportState()
    }

    fun bindUdpSocket(socket: DatagramSocket): Boolean {
        return networkTransport.bindUdpSocket(socket)
    }

    /**
     * Performs a fast ping to the controller to check for reachability over the bound
     * local network (USB-C Ethernet priority -> Wi-Fi Bound -> System Default) and records
     * round-trip latency in milliseconds.
     */
    fun ping(host: String): Boolean {
        val url = cleanUrl(host, "/json/state")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val startMs = SystemClock.elapsedRealtime()
        return try {
            val client = networkTransport.getHttpClient()
            client.newCall(request).execute().use { response ->
                val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                val ok = response.isSuccessful
                networkTransport.recordPingResult(host, ok, elapsedMs)
                ok
            }
        } catch (e: Exception) {
            // Check if direct USB-C Serial WLED device is active as fallback
            val serialOk = networkTransport.trySendUsbSerialJson("""{"v":true}""")
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            networkTransport.recordPingResult(host, serialOk, if (serialOk) elapsedMs else null)
            serialOk
        }
    }

    /**
     * Fetches WLED presets from the controller at /presets.json
     */
    fun fetchPresets(host: String): List<WledPreset>? {
        val url = cleanUrl(host, "/presets.json")
        val request = Request.Builder().url(url).get().build()
        return try {
            networkTransport.getHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val jsonObject = org.json.JSONObject(bodyString)
                val presetsList = mutableListOf<WledPreset>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val id = key.toIntOrNull() ?: continue
                    if (id == 0) continue
                    val presetObj = jsonObject.optJSONObject(key) ?: continue
                    val name = presetObj.optString("n", "Preset $id")
                    presetsList.add(WledPreset(id, name))
                }
                presetsList.sortedBy { it.id }
            }
        } catch (e: Exception) {
            Log.d("WledController", "WLED unreachable while fetching presets ($host): ${e.message}")
            null
        }
    }

    /**
     * Fetches WLED speed effects from the controller at /json/eff
     */
    fun fetchEffects(host: String): List<String>? {
        val url = cleanUrl(host, "/json/eff")
        val request = Request.Builder().url(url).get().build()
        return try {
            networkTransport.getHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val jsonArray = org.json.JSONArray(bodyString)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                list
            }
        } catch (e: Exception) {
            Log.d("WledController", "WLED unreachable while fetching effects ($host): ${e.message}")
            null
        }
    }

    /**
     * Fetches WLED color palettes from the controller at /json/pal
     */
    fun fetchPalettes(host: String): List<String>? {
        val url = cleanUrl(host, "/json/pal")
        val request = Request.Builder().url(url).get().build()
        return try {
            networkTransport.getHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val jsonArray = org.json.JSONArray(bodyString)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                list
            }
        } catch (e: Exception) {
            Log.d("WledController", "WLED unreachable while fetching palettes ($host): ${e.message}")
            null
        }
    }

    /**
     * Sends the configured WledLook along with dynamic brightness to the ESP32/ESP8266
     * over the bound USB-C Ethernet or Wi-Fi interface (and mirrors over USB Serial if connected).
     */
    fun sendState(host: String, look: WledLook, brightness: Float): Boolean {
        val url = cleanUrl(host, "/json/state")

        // 1. Map look colors from hex to RGB List
        val rgbColors = look.colors.map { hexToRgb(it) }

        // 2. Map brightness (0.0 - 1.0) to (0 - 255)
        val bright8Bit = (brightness * 255).toInt().coerceIn(0, 255)

        // 3. Construct Payload
        val segment = WledSegment(
            col = if (look.presetId == null) rgbColors else null,
            fx = look.effectId,
            fp = look.paletteId ?: 0,
            sx = look.speed ?: 128,
            ix = look.intensity ?: 128
        )

        val payload = WledStatePayload(
            on = bright8Bit > 0,
            bri = bright8Bit,
            ps = look.presetId,
            transition = look.transitionMs / 100,
            seg = if (look.presetId == null) listOf(segment) else null
        )

        val json = payloadAdapter.toJson(payload)

        // Also send over direct USB-C Serial (115200 baud JSON API) if a USB Serial WLED device is plugged in
        val sentViaUsbSerial = networkTransport.trySendUsbSerialJson(json)

        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val startMs = SystemClock.elapsedRealtime()
        val client = networkTransport.getHttpClient()

        return try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    Log.d("WledController", "WLED returned non-200 response: Code ${response.code}")
                } else {
                    networkTransport.recordPingResult(host, true, elapsedMs)
                }
                response.isSuccessful || sentViaUsbSerial
            }
        } catch (e: Exception) {
            // Fast 1-attempt immediate retry for transient 2.4 GHz Wi-Fi frame drop
            try {
                client.newCall(request).execute().use { retryResponse ->
                    val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                    if (retryResponse.isSuccessful) {
                        networkTransport.recordPingResult(host, true, elapsedMs)
                    }
                    retryResponse.isSuccessful || sentViaUsbSerial
                }
            } catch (retryEx: Exception) {
                Log.d("WledController", "WLED unreachable while sending state to $host: ${retryEx.message}")
                sentViaUsbSerial
            }
        }
    }

    private fun cleanUrl(host: String, path: String): String {
        var clean = host.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length - 1)
        }
        return "$clean$path"
    }

    private fun hexToRgb(hex: String): List<Int> {
        val cleanHex = hex.trim().removePrefix("#")
        return try {
            if (cleanHex.length == 6) {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                listOf(r, g, b)
            } else if (cleanHex.length == 3) {
                val r = (cleanHex.substring(0, 1) + cleanHex.substring(0, 1)).toInt(16)
                val g = (cleanHex.substring(1, 2) + cleanHex.substring(1, 2)).toInt(16)
                val b = (cleanHex.substring(2, 3) + cleanHex.substring(2, 3)).toInt(16)
                listOf(r, g, b)
            } else {
                listOf(255, 111, 0) // default warm orange
            }
        } catch (e: Exception) {
            listOf(255, 111, 0)
        }
    }
}
