package ir.makanban.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * موتور موقعیت گوشی با LocationManager (بدون نیاز به سرویس‌های گوگل؛ روی همهٔ گوشی‌ها کار می‌کند).
 * به‌روزرسانی هر ۲ ثانیه از دو مسیر GPS و شبکه.
 */
class LocationEngine(
    private val context: Context,
    private val onUpdate: (Location) -> Unit,
) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** آیا سرویس موقعیت گوشی روشن است؟ */
    fun isLocationEnabled(): Boolean = try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) {
        false
    }

    /** آخرین نشانی ذخیره‌شدهٔ گوشی برای نمایش فوری هنگام شروع */
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return try {
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                gps != null && net != null -> if (gps.time > net.time) gps else net
                gps != null -> gps
                else -> net
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** شروع دریافت موقعیت هر ۲ ثانیه */
    fun start() {
        if (running || !hasPermission()) return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
                running = true
            }
        } catch (_: SecurityException) { }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
                running = true
            }
        } catch (_: SecurityException) { }
    }

    /** توقف دریافت موقعیت */
    fun stop() {
        if (!running) return
        try {
            lm.removeUpdates(this)
        } catch (_: SecurityException) { }
        running = false
    }

    override fun onLocationChanged(location: Location) {
        onUpdate(location)
    }
}
