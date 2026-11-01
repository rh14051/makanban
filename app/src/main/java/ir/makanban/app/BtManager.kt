package ir.makanban.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** وضعیت اتصال بلوتوث */
enum class BtState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * مدیریت اتصال بلوتوث کلاسیک (SPP) به گیرندهٔ مکان‌بان.
 * - فهرست دستگاه‌های جفت‌شده از سمت MainActivity خوانده می‌شود.
 * - اتصال با UUID استاندارد سریال و در صورت لزوم با روش ناامن تکرار می‌شود.
 * - خطوط دریافتی (پاسخ ACK) خط‌به‌خط تحویل داده می‌شوند.
 */
class BtManager(
    private val context: Context,
    private val onStateChanged: (BtState, BluetoothDevice?) -> Unit,
    private val onLineReceived: (String) -> Unit,
    private val onError: (Int) -> Unit,
) {
    companion object {
        /** UUID استاندارد پروفایل سریال */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val lock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    @Volatile private var closed = false

    @Volatile private var state: BtState = BtState.DISCONNECTED

    /** آداپتور بلوتوث گوشی (در نبود سخت‌افزار null برمی‌گردد) */
    fun getAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /** اتصال به دستگاه انتخاب‌شده؛ نتیجه از طریق onStateChanged اعلام می‌شود */
    fun connect(device: BluetoothDevice) {
        disconnect()
        closed = false
        setState(BtState.CONNECTING, device)
        Thread {
            var sock: BluetoothSocket? = null
            try {
                try {
                    sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    sock.connect()
                } catch (first: SecurityException) {
                    throw first
                } catch (first: IOException) {
                    // تلاش دوم با کانال ناامن؛ برخی ماژول‌های سری فقط با این روش پاسخ می‌دهند
                    try { sock?.close() } catch (_: IOException) { }
                    sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    sock.connect()
                }
                if (closed) {
                    try { sock?.close() } catch (_: IOException) { }
                    return@Thread
                }
                synchronized(lock) {
                    socket = sock
                    outStream = sock.outputStream
                }
                setState(BtState.CONNECTED, device)
                readLoop(sock)
            } catch (e: SecurityException) {
                if (!closed) {
                    setState(BtState.DISCONNECTED, null)
                    onError(R.string.bt_denied)
                }
            } catch (e: IOException) {
                if (!closed) {
                    setState(BtState.DISCONNECTED, null)
                    onError(R.string.bt_connect_failed)
                }
            }
        }.apply {
            name = "makanban-connect"
            start()
        }
    }

    /** خواندن پیوستهٔ پاسخ‌های گیرنده */
    private fun readLoop(sock: BluetoothSocket) {
        Thread {
            val input: InputStream = try {
                sock.inputStream
            } catch (_: IOException) {
                if (!closed) { setState(BtState.DISCONNECTED, null); onError(R.string.bt_link_lost) }
                return@Thread
            }
            val sb = StringBuilder()
            val buf = ByteArray(1)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val c = buf[0].toInt().toChar()
                    if (c == '\n') {
                        val line = sb.toString().trim()
                        sb.setLength(0)
                        if (line.isNotEmpty()) onLineReceived(line)
                    } else if (c != '\r' && sb.length < 256) {
                        sb.append(c)
                    }
                }
            } catch (_: IOException) { }
            try { sock.close() } catch (_: IOException) { }
            if (!closed) {
                setState(BtState.DISCONNECTED, null)
                onError(R.string.bt_link_lost)
            }
        }.apply {
            name = "makanban-read"
            start()
        }
    }

    /** ارسال یک خط به گیرنده؛ نتیجه به‌صورت غیرهمزمان برمی‌گردد */
    fun send(line: String, onResult: (Boolean) -> Unit) {
        val out: OutputStream? = synchronized(lock) { outStream }
        if (out == null) {
            onResult(false)
            return
        }
        writeExecutor.execute {
            val ok = try {
                synchronized(lock) {
                    out.write((line + "\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                true
            } catch (_: Exception) {
                false
            }
            onResult(ok)
        }
    }

    /** قطع اتصال و بستن سوکت */
    fun disconnect() {
        closed = true
        val s = synchronized(lock) { socket }
        try { s?.close() } catch (_: IOException) { }
        synchronized(lock) {
            socket = null
            outStream = null
        }
        setState(BtState.DISCONNECTED, null)
    }

    private fun setState(s: BtState, d: BluetoothDevice?) {
        state = s
        onStateChanged(s, d)
    }
}
