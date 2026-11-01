package ir.makanban.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ir.makanban.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var bt: BtManager
    private lateinit var locEngine: LocationEngine

    private var lastLocation: Location? = null
    private var isConnected = false
    private var detailsShown = false
    private var pendingDeviceAction: (() -> Unit)? = null
    private val logLines = ArrayDeque<String>()

    // ---------- مجوزها ----------

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                onLocationPermissionReady()
            } else {
                binding.txtGpsStatus.setText(R.string.permission_needed)
                toast(R.string.permission_needed)
            }
        }

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDeviceAction
            pendingDeviceAction = null
            if (granted && action != null) {
                action()
            } else {
                toast(R.string.bt_permission_needed)
            }
        }

    // ---------- چرخهٔ عمر ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        bt = BtManager(
            this,
            onStateChanged = { s, d -> runOnUiThread { onBtState(s, d) } },
            onLineReceived = { line -> runOnUiThread { onModuleLine(line) } },
            onError = { msgRes -> runOnUiThread { addLog(getString(msgRes)) } }
        )
        locEngine = LocationEngine(this) { l -> runOnUiThread { onNewLocation(l) } }

        binding.btnChooseDevice.setOnClickListener { onChooseDeviceClicked() }
        binding.btnDisconnect.setOnClickListener { bt.disconnect() }
        binding.btnSendLocation.setOnClickListener { sendLocation() }
        binding.btnSendTime.setOnClickListener { sendTimeOnly() }
        binding.btnOpenGps.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) { }
        }
        binding.btnDetails.setOnClickListener { requestPlaceDetails() }

        binding.txtAck.setText(R.string.ack_waiting)
        requestLocationPermissions()
        ui.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) onLocationPermissionReady()
    }

    override fun onPause() {
        super.onPause()
        locEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        ui.removeCallbacksAndMessages(null)
        locEngine.stop()
        bt.disconnect()
    }

    // ---------- فاصلهٔ ایمن از دوربین سلفی (بالا) و نوار هوم (پایین) ----------

    private fun setupEdgeToEdge() {
        // فقط از اندروید ۹ به بعد؛ در اندروید ۸ رفتار کلاسیک خودش محتوا را زیر نوارها نمی‌برد
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                maxOf(bars.bottom, cutout.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------- مجوز موقعیت ----------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions() {
        if (hasLocationPermission()) {
            onLocationPermissionReady()
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            locationPermLauncher.launch(perms.toTypedArray())
        }
    }

    private fun onLocationPermissionReady() {
        if (!locEngine.isLocationEnabled()) {
            binding.txtGpsStatus.setText(R.string.gps_off)
            binding.btnOpenGps.visibility = View.VISIBLE
        } else {
            binding.btnOpenGps.visibility = View.GONE
            binding.txtGpsStatus.setText(R.string.gps_searching)
            locEngine.start()
            locEngine.lastKnown()?.let { onNewLocation(it) }
        }
    }

    // ---------- موقعیت زنده (هر ۲ ثانیه) ----------

    private val ticker = object : Runnable {
        override fun run() {
            refreshLocationViews()
            ui.postDelayed(this, 2000L)
        }
    }

    private fun onNewLocation(l: Location) {
        lastLocation = l
        refreshLocationViews()
        if (detailsShown) fillGpsExtras(l)
    }

    private fun refreshLocationViews() {
        val l = lastLocation
        if (l == null) {
            binding.txtLatValue.setText(R.string.val_unknown)
            binding.txtLonValue.setText(R.string.val_unknown)
        } else {
            binding.txtLatValue.text = String.format(Locale.US, "%.6f", l.latitude)
            binding.txtLonValue.text = String.format(Locale.US, "%.6f", l.longitude)
        }
        when {
            !locEngine.isLocationEnabled() -> {
                binding.txtGpsStatus.setText(R.string.gps_off)
                binding.btnOpenGps.visibility = View.VISIBLE
            }
            l == null -> {
                binding.txtGpsStatus.setText(R.string.gps_searching)
                binding.btnOpenGps.visibility = View.GONE
            }
            else -> {
                binding.txtGpsStatus.setText(R.string.gps_active)
                binding.btnOpenGps.visibility = View.GONE
            }
        }
    }

    // ---------- بلوتوث ----------

    private fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun onChooseDeviceClicked() {
        val adapter = bt.getAdapter()
        if (adapter == null) {
            toast(R.string.bt_unavailable)
            return
        }
        if (!adapter.isEnabled) {
            toast(R.string.bt_unavailable)
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) { }
            return
        }
        if (!hasBtConnectPermission()) {
            pendingDeviceAction = { onChooseDeviceClicked() }
            if (Build.VERSION.SDK_INT >= 31) {
                btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                toast(R.string.bt_permission_needed)
            }
            return
        }

        val paired: List<BluetoothDevice> = try {
            adapter.bondedDevices.orEmpty().filterNotNull().sortedBy { safeDeviceName(it) }
        } catch (_: SecurityException) {
            emptyList()
        }

        if (paired.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_device_title)
                .setMessage(R.string.bt_no_paired)
                .setPositiveButton(R.string.bt_open_settings) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (_: Exception) { }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val names = paired.map { deviceLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_device_title)
            .setItems(names) { _, which -> bt.connect(paired[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun safeDeviceName(d: BluetoothDevice): String = try {
        d.name ?: d.address
    } catch (_: SecurityException) {
        d.address
    }

    private fun deviceLabel(d: BluetoothDevice?): String {
        if (d == null) return getString(R.string.val_unknown)
        return try {
            val n = d.name
            if (n.isNullOrBlank()) d.address else n + "  (" + d.address + ")"
        } catch (_: SecurityException) {
            d.address
        }
    }

    private fun onBtState(state: BtState, device: BluetoothDevice?) {
        when (state) {
            BtState.CONNECTED -> {
                isConnected = true
                binding.txtConnChip.setText(R.string.chip_connected)
                binding.txtConnChip.setBackgroundResource(R.drawable.bg_chip_ok)
                binding.txtDeviceStatus.text = getString(R.string.status_connected_to, deviceLabel(device))
                binding.btnDisconnect.isEnabled = true
                addLog(getString(R.string.log_connect))
            }
            BtState.CONNECTING -> {
                isConnected = false
                binding.txtConnChip.setText(R.string.chip_connecting)
                binding.txtConnChip.setBackgroundResource(R.drawable.bg_chip)
                binding.txtDeviceStatus.text = getString(R.string.connecting_to, deviceLabel(device))
                binding.btnDisconnect.isEnabled = false
            }
            BtState.DISCONNECTED -> {
                val wasConnected = isConnected
                isConnected = false
                binding.txtConnChip.setText(R.string.chip_disconnected)
                binding.txtConnChip.setBackgroundResource(R.drawable.bg_chip)
                binding.txtDeviceStatus.setText(R.string.status_disconnected)
                binding.btnDisconnect.isEnabled = false
                if (wasConnected) addLog(getString(R.string.log_disconnect))
            }
        }
    }

    // ---------- دریافت ACK ----------

    private fun onModuleLine(line: String) {
        binding.txtAck.text = getString(R.string.ack_received, line)
        binding.txtAck.setTextColor(ContextCompat.getColor(this, R.color.md_primary))
        addLog(getString(R.string.log_recv, line))
    }

    // ---------- ارسال ----------

    /** خط ارسالی: lat,lon,تاریخ شمسی,ساعت — مثل 35.689200,51.389000,1405/08/10,14:30:25 */
    private fun sendLocation() {
        if (!isConnected) {
            toast(R.string.send_not_connected)
            return
        }
        val l = lastLocation
        if (l == null) {
            toast(R.string.no_fix_yet)
            return
        }
        val line = String.format(
            Locale.US, "%.6f,%.6f,%s,%s",
            l.latitude, l.longitude, JalaliDate.todayJalali(), JalaliDate.nowTime()
        )
        doSend(line)
    }

    /** فقط تاریخ و ساعت شمسی گوشی: T,1405/08/10,14:30:25 */
    private fun sendTimeOnly() {
        if (!isConnected) {
            toast(R.string.send_not_connected)
            return
        }
        doSend("T," + JalaliDate.todayJalali() + "," + JalaliDate.nowTime())
    }

    private fun doSend(line: String) {
        bt.send(line) { ok ->
            runOnUiThread {
                if (ok) {
                    addLog(getString(R.string.log_sent, line))
                    binding.txtAck.setText(R.string.ack_pending)
                    binding.txtAck.setTextColor(
                        ContextCompat.getColor(this, R.color.md_on_surface_variant)
                    )
                } else {
                    addLog(getString(R.string.log_send_failed))
                    toast(R.string.send_not_connected)
                }
            }
        }
    }

    // ---------- جزئیات مکان ----------

    private fun requestPlaceDetails() {
        val l = lastLocation
        if (l == null) {
            toast(R.string.no_fix_yet)
            return
        }
        fillGpsExtras(l)
        binding.txtDetailsMessage.setText(R.string.details_loading)
        binding.txtDetailsMessage.setTextColor(
            ContextCompat.getColor(this, R.color.md_on_surface_variant)
        )
        scope.launch {
            val result = PlaceFinder.find(applicationContext, l.latitude, l.longitude)
            when (result) {
                is PlaceResult.Success -> {
                    val d = result.details
                    binding.valCountry.text = d.country ?: getString(R.string.val_unknown)
                    binding.valProvince.text = d.province ?: getString(R.string.val_unknown)
                    binding.valCity.text = d.city ?: getString(R.string.val_unknown)
                    binding.valNeighbourhood.text = d.neighbourhood ?: getString(R.string.val_unknown)
                    binding.valSource.setText(
                        when (d.source) {
                            0 -> R.string.source_geocoder
                            1 -> R.string.source_nominatim
                            else -> R.string.source_cache
                        }
                    )
                    binding.txtDetailsMessage.setText(R.string.details_hint)
                    detailsShown = true
                }
                is PlaceResult.Failure -> {
                    binding.txtDetailsMessage.text = result.message
                    binding.txtDetailsMessage.setTextColor(
                        ContextCompat.getColor(this@MainActivity, R.color.md_error)
                    )
                }
            }
        }
    }

    /** دقت، ارتفاع و سرعت مستقیماً از نشانی GPS (بدون اینترنت) */
    private fun fillGpsExtras(l: Location) {
        binding.valAccuracy.text =
            if (l.hasAccuracy()) getString(R.string.fmt_accuracy, String.format(Locale.US, "%.0f", l.accuracy))
            else getString(R.string.val_unknown)
        binding.valAltitude.text =
            if (l.hasAltitude()) getString(R.string.fmt_meters, String.format(Locale.US, "%.1f", l.altitude))
            else getString(R.string.val_unknown)
        binding.valSpeed.text =
            if (l.hasSpeed()) getString(R.string.fmt_speed, String.format(Locale.US, "%.1f", l.speed * 3.6f))
            else getString(R.string.val_unknown)
    }

    // ---------- ابزارها ----------

    private fun addLog(msg: String) {
        logLines.addLast(JalaliDate.nowTime() + " — " + msg)
        while (logLines.size > 6) logLines.removeFirst()
        binding.txtLog.text = logLines.joinToString("\n")
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
