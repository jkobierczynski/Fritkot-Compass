package be.fritkot.compass

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Looks up frituren / fritkots / friteries near a point using the
 * OpenStreetMap Overpass API, running the whole thing on a background
 * thread. Falls back to a small bundled offline sample if every mirror
 * is unreachable (no connection, Overpass rate limiting, etc.) so the
 * app still shows something useful.
 *
 * Note: this talks to the public Overpass API directly from the user's
 * device — it needs the device's own internet connection, not the build
 * machine's.
 */
class OverpassClient(private val appContext: Context) {

    interface Callback {
        fun onResult(fritkots: List<Fritkot>, usedOfflineFallback: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    fun fetchNearby(lat: Double, lon: Double, radiusMeters: Int, callback: Callback) {
        Thread {
            val result = tryMirrors(lat, lon, radiusMeters)
            if (result != null) {
                mainHandler.post { callback.onResult(result, false) }
            } else {
                val fallback = loadOfflineFallback()
                mainHandler.post { callback.onResult(fallback, true) }
            }
        }.start()
    }

    private fun tryMirrors(lat: Double, lon: Double, radiusMeters: Int): List<Fritkot>? {
        val query = buildQuery(lat, lon, radiusMeters)
        for (base in mirrors) {
            try {
                val json = postQuery(base, query)
                val parsed = parseElements(json)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                // try next mirror
            }
        }
        return null
    }

    private fun buildQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        // Belgian fry shops are usually tagged amenity=fast_food with a
        // cuisine of "friture" on OpenStreetMap; some are only identifiable
        // by name, so we also match common name spellings as a fallback.
        return """
            [out:json][timeout:25];
            (
              node["amenity"="fast_food"]["cuisine"~"friture|frites|fries",i](around:$radiusMeters,$lat,$lon);
              node["amenity"="fast_food"]["name"~"friture|frituur|fritkot|frite",i](around:$radiusMeters,$lat,$lon);
            );
            out body;
        """.trimIndent()
    }

    private fun postQuery(baseUrl: String, query: String): JSONObject {
        val url = URL(baseUrl)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code from $baseUrl")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseElements(json: JSONObject): List<Fritkot> {
        val elements: JSONArray = json.optJSONArray("elements") ?: JSONArray()
        val results = ArrayList<Fritkot>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "node") continue
            val id = el.optLong("id")
            val lat = el.optDouble("lat", Double.NaN)
            val lon = el.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val tags = el.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
            val address = buildAddress(tags)

            results.add(Fritkot(id = id, name = name, lat = lat, lon = lon, address = address))
        }
        return results
    }

    private fun buildAddress(tags: JSONObject?): String {
        if (tags == null) return ""
        val street = tags.optString("addr:street", "")
        val houseNumber = tags.optString("addr:housenumber", "")
        val city = tags.optString("addr:city", "")
        val parts = ArrayList<String>()
        if (street.isNotBlank()) {
            parts.add(if (houseNumber.isNotBlank()) "$street $houseNumber" else street)
        }
        if (city.isNotBlank()) parts.add(city)
        return parts.joinToString(", ")
    }

    /** Small hand-picked set of well-known Belgian fritkots, bundled as an asset for offline use. */
    private fun loadOfflineFallback(): List<Fritkot> {
        return try {
            val text = appContext.assets.open("fritkots_fallback.json")
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            val results = ArrayList<Fritkot>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                results.add(
                    Fritkot(
                        id = o.optLong("id", i.toLong()),
                        name = o.optString("name"),
                        lat = o.optDouble("lat"),
                        lon = o.optDouble("lon"),
                        address = o.optString("address", "")
                    )
                )
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
