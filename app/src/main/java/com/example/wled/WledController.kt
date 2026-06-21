package com.example.wled

import android.util.Log
import com.example.config.WledLook
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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

class WledController {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val payloadAdapter = moshi.adapter(WledStatePayload::class.java)

    // Setup an OkHttp client with a fast 1-second timeout as required by System Design Section 6
    private val client = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) // connection keep-alive
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Performs a fast ping to the controller to check for reachability.
     */
    fun ping(host: String): Boolean {
        val url = cleanUrl(host, "/json/state")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetches WLED presets from the controller at /presets.json
     */
    fun fetchPresets(host: String): List<WledPreset>? {
        val url = cleanUrl(host, "/presets.json")
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
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
            Log.e("WledController", "Error fetching presets: ${e.message}")
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
            client.newCall(request).execute().use { response ->
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
            Log.e("WledController", "Error fetching effects: ${e.message}")
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
            client.newCall(request).execute().use { response ->
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
            Log.e("WledController", "Error fetching palettes: ${e.message}")
            null
        }
    }

    /**
     * Sends the configured WledLook along with dynamic brightness to the ESP32.
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
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WledController", "Failed to send request: Code ${response.code}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("WledController", "Exception while sending state to WLED at $host: ${e.message}")
            false
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
