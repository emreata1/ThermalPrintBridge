package com.emreata.thermalprinterbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class DashboardActivity : AppCompatActivity() {

    private lateinit var btnChangeLanguage: Button
    private lateinit var tvActivePrinterName: TextView
    private lateinit var tvActivePrinterMac: TextView
    private lateinit var btnQuickTest: Button
    private lateinit var tvCopyCount: TextView
    private lateinit var btnMinusCopy: Button
    private lateinit var btnPlusCopy: Button
    private lateinit var tvFeedCount: TextView
    private lateinit var btnMinusFeed: Button
    private lateinit var btnPlusFeed: Button
    private lateinit var swManualCopyConfirm: SwitchCompat
    private lateinit var layoutAutoSeconds: View
    private lateinit var tvAutoSeconds: TextView
    private lateinit var btnMinusSeconds: Button
    private lateinit var btnPlusSeconds: Button
    private lateinit var ivLogoPreview: ImageView
    private lateinit var btnSelectLogo: Button
    private lateinit var btnRemoveLogo: Button
    private lateinit var prefs: SharedPreferences

    private var activePrinterAddress: String? = null
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    companion object {
        const val KEY_COPIES = "default_copy_count"
        const val KEY_MANUAL_COPY_CONFIRM = "manual_copy_confirm"
        const val KEY_AUTO_WAIT_SECONDS = "auto_wait_seconds"
        const val KEY_EXTRA_FEED_LINES = "extra_feed_lines"
        const val LOGO_FILE_NAME = "printer_header_logo.png"
    }

    private var pendingBluetoothAction: (() -> Unit)? = null

    // Bluetooth acma Intent firlaticisi
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingBluetoothAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.bt_required_test), Toast.LENGTH_SHORT).show()
        }
        pendingBluetoothAction = null
    }

    // Android 12+ icin CONNECT ve SCAN izinlerini birlikte isteyen launcher
    @SuppressLint("InlinedApi")
    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        if (connectGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ensureBluetoothEnabled {
                activePrinterAddress?.let { testPrint(it) }
            }
        } else {
            Toast.makeText(this, getString(R.string.bt_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(it, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { saveLogoLocally(it) }
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_dashboard)

        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        btnChangeLanguage = findViewById(R.id.btnChangeLanguage)
        tvActivePrinterName = findViewById(R.id.tvActivePrinterName)
        tvActivePrinterMac = findViewById(R.id.tvActivePrinterMac)
        btnQuickTest = findViewById(R.id.btnQuickTest)
        tvCopyCount = findViewById(R.id.tvCopyCount)
        btnMinusCopy = findViewById(R.id.btnMinusCopy)
        btnPlusCopy = findViewById(R.id.btnPlusCopy)
        tvFeedCount = findViewById(R.id.tvFeedCount)
        btnMinusFeed = findViewById(R.id.btnMinusFeed)
        btnPlusFeed = findViewById(R.id.btnPlusFeed)
        swManualCopyConfirm = findViewById(R.id.swManualCopyConfirm)
        layoutAutoSeconds = findViewById(R.id.layoutAutoSeconds)
        tvAutoSeconds = findViewById(R.id.tvAutoSeconds)
        btnMinusSeconds = findViewById(R.id.btnMinusSeconds)
        btnPlusSeconds = findViewById(R.id.btnPlusSeconds)
        ivLogoPreview = findViewById(R.id.ivLogoPreview)
        btnSelectLogo = findViewById(R.id.btnSelectLogo)
        btnRemoveLogo = findViewById(R.id.btnRemoveLogo)

        setupLanguageToggle()

        var copies = prefs.getInt(KEY_COPIES, 1)
        tvCopyCount.text = copies.toString()

        btnMinusCopy.setOnClickListener {
            if (copies > 1) {
                copies--
                prefs.edit().putInt(KEY_COPIES, copies).apply()
                tvCopyCount.text = copies.toString()
            }
        }

        btnPlusCopy.setOnClickListener {
            if (copies < 10) {
                copies++
                prefs.edit().putInt(KEY_COPIES, copies).apply()
                tvCopyCount.text = copies.toString()
            }
        }

        var feedLines = prefs.getInt(KEY_EXTRA_FEED_LINES, 3)
        tvFeedCount.text = feedLines.toString()

        btnMinusFeed.setOnClickListener {
            if (feedLines > 0) {
                feedLines--
                prefs.edit().putInt(KEY_EXTRA_FEED_LINES, feedLines).apply()
                tvFeedCount.text = feedLines.toString()
            }
        }

        btnPlusFeed.setOnClickListener {
            if (feedLines < 10) {
                feedLines++
                prefs.edit().putInt(KEY_EXTRA_FEED_LINES, feedLines).apply()
                tvFeedCount.text = feedLines.toString()
            }
        }

        var waitSec = prefs.getInt(KEY_AUTO_WAIT_SECONDS, 4)
        tvAutoSeconds.text = getString(R.string.seconds_suffix, waitSec)

        btnMinusSeconds.setOnClickListener {
            if (waitSec > 2) {
                waitSec--
                prefs.edit().putInt(KEY_AUTO_WAIT_SECONDS, waitSec).apply()
                tvAutoSeconds.text = getString(R.string.seconds_suffix, waitSec)
            }
        }

        btnPlusSeconds.setOnClickListener {
            if (waitSec < 10) {
                waitSec++
                prefs.edit().putInt(KEY_AUTO_WAIT_SECONDS, waitSec).apply()
                tvAutoSeconds.text = getString(R.string.seconds_suffix, waitSec)
            }
        }

        val isManual = prefs.getBoolean(KEY_MANUAL_COPY_CONFIRM, true)
        swManualCopyConfirm.isChecked = isManual
        layoutAutoSeconds.visibility = if (isManual) View.GONE else View.VISIBLE

        swManualCopyConfirm.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MANUAL_COPY_CONFIRM, isChecked).apply()
            layoutAutoSeconds.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        btnSelectLogo.setOnClickListener { pickLogoLauncher.launch("image/png") }
        btnRemoveLogo.setOnClickListener {
            val file = File(filesDir, LOGO_FILE_NAME)
            if (file.exists()) file.delete()
            loadLogoPreview()
            Toast.makeText(this, getString(R.string.logo_removed), Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.cardManagePrinters).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.cardPickPdf).setOnClickListener {
            pickPdfLauncher.launch("application/pdf")
        }

        btnQuickTest.setOnClickListener {
            val address = activePrinterAddress
            if (address == null) {
                Toast.makeText(this, getString(R.string.no_defined_printer), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasConnect = ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

                val hasScan = ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasConnect || !hasScan) {
                    requestBluetoothPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                        )
                    )
                    return@setOnClickListener
                }
            }

            ensureBluetoothEnabled {
                testPrint(address)
            }
        }
    }

    private fun setupLanguageToggle() {
        val currentLocale = LocaleHelper.getCurrentLocale()
        val isTr = currentLocale.startsWith("tr")
        btnChangeLanguage.text = if (isTr) getString(R.string.lang_badge_tr) else getString(R.string.lang_badge_en)

        btnChangeLanguage.setOnClickListener {
            val languages = arrayOf(
                getString(R.string.lang_option_tr),
                getString(R.string.lang_option_en)
            )
            val checkedItem = if (LocaleHelper.getCurrentLocale().startsWith("tr")) 0 else 1

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_select_language))
                .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                    val selectedCode = if (which == 0) "tr" else "en"
                    val currentCode = if (LocaleHelper.getCurrentLocale().startsWith("tr")) "tr" else "en"
                    if (selectedCode != currentCode) {
                        LocaleHelper.setAppLocale(selectedCode)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
        }
    }

    private fun ensureBluetoothEnabled(onReady: () -> Unit) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            Toast.makeText(this, getString(R.string.bt_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            pendingBluetoothAction = onReady
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        } else {
            onReady()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboardState()
        loadLogoPreview()
    }

    private fun saveLogoLocally(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val outFile = File(filesDir, LOGO_FILE_NAME)
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            loadLogoPreview()
            Toast.makeText(this, getString(R.string.logo_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.logo_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLogoPreview() {
        val file = File(filesDir, LOGO_FILE_NAME)

        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ivLogoPreview.setImageBitmap(bmp)
            btnRemoveLogo.isEnabled = true
        } else {
            ivLogoPreview.setImageDrawable(null)
            btnRemoveLogo.isEnabled = false
        }
    }

    private fun updateDashboardState() {
        val json = prefs.getString(SettingsActivity.KEY_SAVED_PRINTERS_JSON, null)
        val lastUsedMac = prefs.getString("last_used_printer_mac", null)

        val savedList = mutableListOf<SavedPrinter>()
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<SavedPrinter>>() {}.type
                savedList.addAll(Gson().fromJson(json, type))
            } catch (e: Exception) {
                prefs.edit().remove(SettingsActivity.KEY_SAVED_PRINTERS_JSON).apply()
            }
        }

        if (savedList.isEmpty()) {
            tvActivePrinterName.text = getString(R.string.no_defined_printer)
            tvActivePrinterMac.text = getString(R.string.add_printer_desc)
            btnQuickTest.isEnabled = false
            activePrinterAddress = null
            return
        }

        val active = savedList.find { it.address == lastUsedMac } ?: savedList.first()
        activePrinterAddress = active.address
        tvActivePrinterName.text = active.customName
        tvActivePrinterMac.text = "${active.realName} • ${active.address}"
        btnQuickTest.isEnabled = true
    }

    private fun connectWithRetry(device: BluetoothDevice, maxAttempts: Int = 3): BluetoothSocket {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                return socket
            } catch (e: Exception) {
                lastException = e
                Thread.sleep((attempt * 600).toLong())
            }
        }
        throw lastException ?: Exception("Printer connection failed.")
    }

    @SuppressLint("InlinedApi")
    private fun testPrint(address: String) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter?.getRemoteDevice(address) ?: return

        btnQuickTest.isEnabled = false
        Toast.makeText(this, getString(R.string.preparing_test_print), Toast.LENGTH_SHORT).show()

        thread {
            var socket: BluetoothSocket? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread {
                        btnQuickTest.isEnabled = true
                        Toast.makeText(this@DashboardActivity, getString(R.string.bt_permission_required), Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                socket = connectWithRetry(device, maxAttempts = 3)
                val os = socket.outputStream

                val testBitmap = createPangramTestBitmap()

                os.write(byteArrayOf(0x1B, 0x40)) // ESC @ Reset
                os.flush()
                Thread.sleep(60)

                os.write(byteArrayOf(0x1B, 0x33, 24))
                os.flush()

                printTestRaster(testBitmap, os)

                val extraLines = prefs.getInt(KEY_EXTRA_FEED_LINES, 3)
                val totalFeed = (3 + extraLines).coerceIn(1, 20).toByte()

                os.write(byteArrayOf(0x1B, 0x32))
                os.write(byteArrayOf(0x1B, 0x64, totalFeed))
                os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
                os.flush()

                Thread.sleep(1200)

                runOnUiThread {
                    Toast.makeText(this, getString(R.string.test_print_success), Toast.LENGTH_SHORT).show()
                    btnQuickTest.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    btnQuickTest.isEnabled = true
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun createPangramTestBitmap(): Bitmap {
        val width = 576
        val height = 400
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paintTitle = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = false
        }

        val paintLabel = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            isFakeBoldText = true
            isAntiAlias = false
        }

        val paintText = Paint().apply {
            color = Color.BLACK
            textSize = 19f
            isAntiAlias = false
        }

        val paintDivider = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
        }

        var y = 36f
        canvas.drawText("--- THERMAL PRINTER HARDWARE TEST ---", 24f, y, paintTitle)

        y += 18f
        canvas.drawLine(10f, y, 566f, y, paintDivider)

        y += 36f
        canvas.drawText("The quick brown fox jumps over the lazy dog.", 20f, y, paintText)
        y += 28f
        canvas.drawText("Pijamalı hasta, yağız şoföre çabucak güvendi.", 20f, y, paintText)

        y += 34f
        canvas.drawText("0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ", 20f, y, paintText)

        y += 38f
        canvas.drawText("Characters & Symbols:", 20f, y, paintLabel)
        y += 26f
        canvas.drawText(". , : ; ! ? ' \" ` ~ @ # $ % ^ & * ( ) _ + - = /", 20f, y, paintText)
        y += 26f
        canvas.drawText("[ ] { } < > | \\ ₺ € $ £", 20f, y, paintText)

        y += 20f
        canvas.drawLine(10f, y, 566f, y, paintDivider)

        return bmp
    }

    private fun printTestRaster(bitmap: Bitmap, outputStream: java.io.OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val nL = (width and 0xFF).toByte()
        val nH = ((width shr 8) and 0xFF).toByte()

        var y = 0
        while (y < height) {
            val lineStream = ByteArrayOutputStream()
            var isEntireLineBlank = true
            lineStream.write(byteArrayOf(0x1B, 0x2A, 33, nL, nH))

            for (x in 0 until width) {
                for (k in 0 until 3) {
                    var slice: Byte = 0
                    for (b in 0 until 8) {
                        val currentY = y + (k * 8) + b
                        if (currentY < height) {
                            val pixel = bitmap.getPixel(x, currentY)
                            if (pixel == Color.BLACK) {
                                slice = (slice.toInt() or (1 shl (7 - b))).toByte()
                                isEntireLineBlank = false
                            }
                        }
                    }
                    lineStream.write(slice.toInt())
                }
            }
            lineStream.write(0x0A)

            if (isEntireLineBlank) {
                outputStream.write(byteArrayOf(0x1B, 0x4A, 24))
            } else {
                outputStream.write(lineStream.toByteArray())
            }

            outputStream.flush()
            Thread.sleep(8)
            y += 24
        }
    }
}