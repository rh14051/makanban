package ir.makanban.app

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** جزئیات یک نقطهٔ جغرافیایی */
data class PlaceDetails(
    val country: String?,
    val province: String?,
    val city: String?,
    val neighbourhood: String?,
    /** ۰ = سرویس موقعیت گوشی، ۱ = نقشهٔ آزاد آنلاین، ۲ = کش محلی */
    val source: Int,
)

sealed class PlaceResult {
    data class Success(val details: PlaceDetails) : PlaceResult()
    data class Failure(val message: String) : PlaceResult()
}

/**
 * یابندهٔ جزئیات مکان با روش ترکیبی (راه‌حل مشکل «یک بار کار کرد و بعد نه»):
 * ۱) کش محلی (برای نقاط نزدیک، نتیجهٔ قبلی بلافاصله برمی‌گردد)
 * ۲) سرویس مکان‌یابی خود گوشی (Geocoder)
 * ۳) سرویس نقشهٔ آزاد آنلاین (OpenStreetMap / Nominatim)
 * اگر هیچ‌کدام جواب نداد، پیام خطای واضح برمی‌گردد؛ هیچ‌وقت «یک خط خالی» نمایش داده نمی‌شود.
 */
object PlaceFinder {

    private val cache = HashMap<String, PlaceDetails>()

    suspend fun find(context: Context, lat: Double, lon: Double): PlaceResult =
        withContext(Dispatchers.IO) {
            // ۰) کش: کلید با دقت حدود ۱۱۰ متر (سه رقم اعشار)
            val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
            synchronized(cache) {
                cache[key]?.let {
                    return@withContext PlaceResult.Success(it.copy(source = 2))
                }
            }

            // ۱) سرویس مکان‌یابی خود گوشی
            tryGeocoder(context, lat, lon)?.let {
                synchronized(cache) { cache[key] = it }
                return@withContext PlaceResult.Success(it)
            }

            // ۲) نقشهٔ آزاد آنلاین
            tryOnlineMap(lat, lon)?.let {
                synchronized(cache) { cache[key] = it }
                return@withContext PlaceResult.Success(it)
            }

            PlaceResult.Failure(
                "دریافت جزئیات ناموفق بود. اینترنت گوشی را بررسی کنید یا کمی بعد دوباره تلاش کنید."
            )
        }

    /** تلاش با سرویس مکان‌یابی خود گوشی (بدون اینترنت، وقتی سرویس گوگل فعال باشد) */
    private suspend fun tryGeocoder(context: Context, lat: Double, lon: Double): PlaceDetails? {
        if (!Geocoder.isPresent()) return null
        return try {
            val address = withTimeoutOrNull(4500L) {
                val geocoder = Geocoder(context, Locale("fa"))
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            } ?: return null

            val city = address.locality ?: address.subAdminArea ?: address.adminArea
            val nb = address.subLocality ?: address.featureName
            if (address.countryName.isNullOrBlank() && city.isNullOrBlank()) null
            else PlaceDetails(address.countryName, address.adminArea, city, nb, 0)
        } catch (_: Exception) {
            null
        }
    }

    /** تلاش با نقشهٔ آزاد آنلاین (OpenStreetMap) */
    private fun tryOnlineMap(lat: Double, lon: Double): PlaceDetails? {
        var conn: HttpURLConnection? = null
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&addressdetails=1&zoom=18&accept-language=fa" +
                "&lat=" + lat.toString() +
                "&lon=" + lon.toString()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MakanBan/1.0 (Android)")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val addr = obj.optJSONObject("address") ?: return null

            val country = pick(addr, "country")
            val province = pick(addr, "state", "province", "county")
            val city = pick(addr, "city", "town", "village", "municipality")
            val nb = pick(addr, "neighbourhood", "suburb", "quarter", "city_district", "residential")

            if (country == null && province == null && city == null && nb == null) null
            else PlaceDetails(country, province, city, nb, 1)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** اولین مقدار غیرخالی از بین چند کلید JSON */
    private fun pick(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
