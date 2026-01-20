package com.example.ddiscan

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.*
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.hoho.android.usbserial.driver.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var rangeButton: Button

    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val buffer = StringBuilder()
    private val points = ArrayList<Entry>()

    private var xMax = 3000f   // distance in mm
    private var yMax = 700f    // amplitude (ADC)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.ddiscan.USB_PERMISSION"
        private const val BAUD = 115200
        private const val SPEED_OF_SOUND = 343000f // mm/s
    }

    // ================= LIFECYCLE =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chart = findViewById(R.id.chart)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        rangeButton = findViewById(R.id.rangeButton)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setupChart()

        connectButton.setOnClickListener {
            if (serialPort == null) connectUsb() else disconnectUsb()
        }

        rangeButton.setOnClickListener { showRangeDialog() }

        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            usbDetachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectUsb()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbDetachReceiver)
        executor.shutdown()
    }

    // ================= CHART =================

    private fun setupChart() {

        chart.data = LineData(
            LineDataSet(mutableListOf(), "Amplitude (ADC)")
        )

        chart.description.text = "X: Distance (mm)   |   Y: Amplitude (ADC)"

        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setPinchZoom(true)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            axisMinimum = 0f
            axisMaximum = xMax
            setDrawGridLines(true)
            setDrawAxisLine(true)
            textColor = Color.BLACK

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.toInt()} mm"
                }
            }
        }

        chart.axisLeft.apply {
            axisMinimum = 0f
            axisMaximum = yMax
            setDrawGridLines(true)
            setDrawAxisLine(true)
            textColor = Color.BLACK
        }

        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = true
    }

    private fun applyRange() {
        chart.xAxis.axisMaximum = xMax
        chart.axisLeft.axisMaximum = yMax
        chart.invalidate()
    }

    // ================= RANGE DIALOG =================

    private fun showRangeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val xInput = EditText(this).apply {
            hint = "X max (distance in mm)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(xMax.toInt().toString())
        }

        val yInput = EditText(this).apply {
            hint = "Y max (amplitude ADC)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(yMax.toInt().toString())
        }

        layout.addView(xInput)
        layout.addView(yInput)

        AlertDialog.Builder(this)
            .setTitle("Set Graph Range")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                xMax = xInput.text.toString().toFloatOrNull() ?: xMax
                yMax = yInput.text.toString().toFloatOrNull() ?: yMax
                applyRange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= USB =================

    private fun connectUsb() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setStatus("No USB device", false)
            return
        }

        val device = drivers[0].device
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
            return
        }

        openUsb(device)
    }

    private fun openUsb(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
        val connection = usbManager.openDevice(device) ?: return

        serialPort = driver.ports[0]
        serialPort!!.open(connection)
        serialPort!!.setParameters(
            BAUD, 8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        setStatus("Connected", true)
        connectButton.text = "Disconnect"

        startReading()
    }

    private fun disconnectUsb() {
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        setStatus("Disconnected", false)
        connectButton.text = "Connect"
    }

    // ================= SERIAL =================

    private fun startReading() {
        executor.execute {
            val buf = ByteArray(1024)
            while (serialPort != null) {
                try {
                    val len = serialPort!!.read(buf, 200)
                    if (len > 0) parseSerial(String(buf, 0, len))
                } catch (_: Exception) {
                    ui.post { disconnectUsb() }
                    break
                }
            }
        }
    }

    private fun parseSerial(chunk: String) {
        buffer.append(chunk)
        var idx = buffer.indexOf("\n")

        while (idx >= 0) {
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)

            val parts = line.split(",")
            if (parts.size == 2) {
                try {
                    val amp = parts[0].toFloat()
                    val tofUs = parts[1].toFloat()

                    // TOF → distance (mm)
                    val distanceMm = (SPEED_OF_SOUND * tofUs) / 2_000_000f

                    points.add(Entry(distanceMm, 0f))
                    points.add(Entry(distanceMm, amp))
                } catch (_: Exception) {}
            }

            if (points.size >= 300) {
                val snap = ArrayList(points)
                points.clear()
                ui.post { draw(snap) }
            }

            idx = buffer.indexOf("\n")
        }
    }

    // ================= DRAW =================

    private fun draw(data: List<Entry>) {
        val set = LineDataSet(data, "Amplitude (ADC)").apply {
            color = Color.BLUE
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        chart.data = LineData(set)
        chart.invalidate()
    }

    // ================= UI =================

    private fun setStatus(text: String, ok: Boolean) {
        statusText.text = text
        statusText.setTextColor(if (ok) Color.GREEN else Color.RED)
    }

    // ================= RECEIVERS =================

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    ?.let { openUsb(it) }
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            disconnectUsb()
        }
    }
}
