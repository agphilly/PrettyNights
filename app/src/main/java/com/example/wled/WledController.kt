package com.example.wled

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.config.WledHardwareConfig
import com.example.config.WledLook
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket

data class WledPreset(
    val id: Int,
    val name: String
)

data class WledHardwareLiveInfo(
    val version: String,
    val arch: String,
    val ledCount: Int,
    val maxPowerMa: Int,
    val currentPowerMa: Int,
    val freeHeapBytes: Int
)

// Models representing the WLED JSON State payload structure
data class WledSegment(
    val id: Int? = null,
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
     * Fetches live hardware & LED bus info from the WLED controller at /json/info
     */
    fun fetchHardwareInfo(host: String): WledHardwareLiveInfo? {
        val url = cleanUrl(host, "/json/info")
        val request = Request.Builder().url(url).get().build()
        val startMs = SystemClock.elapsedRealtime()
        return try {
            networkTransport.getHttpClient().newCall(request).execute().use { response ->
                val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    networkTransport.recordPingResult(host, false, null)
                    return null
                }
                networkTransport.recordPingResult(host, true, elapsedMs)
                val bodyString = response.body?.string() ?: return null
                val info = JSONObject(bodyString)
                val leds = info.optJSONObject("leds")
                WledHardwareLiveInfo(
                    version = info.optString("ver", "Unknown"),
                    arch = info.optString("arch", "esp32"),
                    ledCount = leds?.optInt("count", 0) ?: 0,
                    maxPowerMa = leds?.optInt("maxpwr", 0) ?: 0,
                    currentPowerMa = leds?.optInt("pwr", 0) ?: 0,
                    freeHeapBytes = info.optInt("freeheap", 0)
                )
            }
        } catch (e: Exception) {
            Log.d("WledController", "Failed to fetch /json/info ($host): ${e.message}")
            null
        }
    }

    /**
     * Pushes the NaveiTech NV-LC616WL GPIO hardware setup (GPIO 16 Ch1, GPIO 2 Ch2, GPIO 13 Aux,
     * and maxpwr current limiter) to /json/cfg and partitions the logical segments on /json/state.
     */
    fun pushHardwareAndSegments(host: String, hw: WledHardwareConfig): Boolean {
        val cfgPayload = buildHardwareCfgJson(hw)
        val segPayload = buildSegmentLayoutJson(hw)

        val client = networkTransport.getHttpClient()
        return try {
            val cfgReq = Request.Builder()
                .url(cleanUrl(host, "/json/cfg"))
                .post(cfgPayload.toRequestBody(jsonMediaType))
                .build()
            val cfgOk = client.newCall(cfgReq).execute().use { it.isSuccessful }

            val segReq = Request.Builder()
                .url(cleanUrl(host, "/json/state"))
                .post(segPayload.toRequestBody(jsonMediaType))
                .build()
            val segOk = client.newCall(segReq).execute().use { it.isSuccessful }

            cfgOk || segOk
        } catch (e: Exception) {
            Log.d("WledController", "Failed to push NV-LC616WL hardware config ($host): ${e.message}")
            false
        }
    }

    internal fun buildHardwareCfgJson(hw: WledHardwareConfig): String {
        val ch1Len = hw.ch1LedCount.coerceIn(1, 1000)
        val ch2Len = hw.ch2LedCount.coerceIn(1, 1000)
        val ch3Len = hw.ch3LedCount.coerceIn(1, 1000)

        val insArray = JSONArray()
        // Bus 0: Channel 1 (IO16 / GPIO 16)
        insArray.put(
            JSONObject().apply {
                put("start", 0)
                put("len", ch1Len)
                put("pin", JSONArray().put(16))
                put("order", hw.ch1ColorOrder)
                put("rev", hw.ch1Reversed)
                put("skip", 0)
                put("type", hw.ch1StripType)
                put("ref", false)
            }
        )

        var nextStart = ch1Len
        // Bus 1: Channel 2 (IO2 / GPIO 2)
        if (hw.ch2Enabled) {
            insArray.put(
                JSONObject().apply {
                    put("start", nextStart)
                    put("len", ch2Len)
                    put("pin", JSONArray().put(2))
                    put("order", hw.ch2ColorOrder)
                    put("rev", hw.ch2Reversed)
                    put("skip", 0)
                    put("type", hw.ch2StripType)
                    put("ref", false)
                }
            )
            nextStart += ch2Len
        }

        // Bus 2: Auxiliary IO13 as 3rd LED strip if selected
        if (hw.io13Role == "STRIP_CH3") {
            insArray.put(
                JSONObject().apply {
                    put("start", nextStart)
                    put("len", ch3Len)
                    put("pin", JSONArray().put(13))
                    put("order", 0)
                    put("rev", false)
                    put("skip", 0)
                    put("type", 22)
                    put("ref", false)
                }
            )
        }

        val ledObj = JSONObject().apply {
            put("maxpwr", hw.maxCurrentMa.coerceIn(500, 15000))
            put("ledma", 55)
            put("ins", insArray)
        }

        // Button 0 on GPIO 13 (unless IO13 is used as 3rd LED strip)
        val btnType = when (hw.io13Role) {
            "PUSHBUTTON" -> 2 // Pushbutton (inverted pull-up)
            "REED_SWITCH" -> 4 // Switch / reed sensor
            else -> 0 // Disabled
        }
        val btnPin = if (btnType > 0) 13 else -1
        val btnIns = JSONArray().put(
            JSONObject().apply {
                put("type", btnType)
                put("pin", JSONArray().put(btnPin))
                put("macros", JSONArray().put(0).put(0).put(0))
            }
        )

        val hwObj = JSONObject().apply {
            put("led", ledObj)
            put("btn", JSONObject().put("ins", btnIns))
        }

        return JSONObject().put("hw", hwObj).toString()
    }

    internal fun buildSegmentLayoutJson(hw: WledHardwareConfig): String {
        val ch1Len = hw.ch1LedCount.coerceIn(1, 1000)
        val ch2Len = hw.ch2LedCount.coerceIn(1, 1000)
        val ch3Len = hw.ch3LedCount.coerceIn(1, 1000)

        val segArray = JSONArray()
        // Segment 0: Channel 1 (IO16)
        segArray.put(
            JSONObject().apply {
                put("id", 0)
                put("start", 0)
                put("stop", ch1Len)
                put("n", hw.ch1ZoneName.ifBlank { "Main Frame (IO16)" })
                put("on", true)
            }
        )

        var nextStart = ch1Len
        // Segment 1: Channel 2 (IO2)
        if (hw.ch2Enabled) {
            segArray.put(
                JSONObject().apply {
                    put("id", 1)
                    put("start", nextStart)
                    put("stop", nextStart + ch2Len)
                    put("n", hw.ch2ZoneName.ifBlank { "Rear Tail (IO2)" })
                    put("on", true)
                }
            )
            nextStart += ch2Len
        } else {
            // Delete Segment 1 by setting stop = 0
            segArray.put(JSONObject().apply { put("id", 1); put("start", 0); put("stop", 0) })
        }

        // Segment 2: Auxiliary IO13 if configured as 3rd LED strip
        if (hw.io13Role == "STRIP_CH3") {
            segArray.put(
                JSONObject().apply {
                    put("id", 2)
                    put("start", nextStart)
                    put("stop", nextStart + ch3Len)
                    put("n", "Aux Strip (IO13)")
                    put("on", true)
                }
            )
        }

        return JSONObject().apply {
            put("on", true)
            put("seg", segArray)
        }.toString()
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

        // 3. Construct Target Segments based on look.targetSegments ("BOTH", "SEG_0_IO16", "SEG_1_IO2")
        val targetSegmentsList = if (look.presetId == null) {
            val targetIds: List<Int?> = when (look.targetSegments) {
                "SEG_0_IO16" -> listOf(0)
                "SEG_1_IO2" -> listOf(1)
                else -> listOf(null) // null targets active main/all segments seamlessly
            }
            targetIds.map { segId ->
                WledSegment(
                    id = segId,
                    col = rgbColors,
                    fx = look.effectId,
                    fp = look.paletteId ?: 0,
                    sx = look.speed ?: 128,
                    ix = look.intensity ?: 128
                )
            }
        } else {
            null
        }

        val payload = WledStatePayload(
            on = bright8Bit > 0,
            bri = bright8Bit,
            ps = look.presetId,
            transition = look.transitionMs / 100,
            seg = targetSegmentsList
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
