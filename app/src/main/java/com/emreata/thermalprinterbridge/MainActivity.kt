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
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var spnPrinters: Spinner
    private lateinit var btnPrint: Button
    private lateinit var btnCancel: Button
    private lateinit var btnOpenExternal: Button
    private lateinit var pbLoading: ProgressBar
    private lateinit var cbIncludePrefix: CheckBox

    private lateinit var prefs: SharedPreferences
    private var renderedBitmap: Bitmap? = null
    private var currentPdfUri: Uri? = null
    private val savedPrinters = mutableListOf<SavedPrinter>()

    @Volatile
    private var isCurrentlyPrinting = false

    private var currentManualCopy = 1
    private var totalCopiesCount = 1

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Bluetooth acma istegi ve bekleyen islem callback'i
    private var pendingPrintAction: (() -> Unit)? = null

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingPrintAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.bt_required_print), Toast.LENGTH_SHORT).show()
        }
        pendingPrintAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()
        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContentView(R.layout.activity_main)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        tvStatus = findViewById(R.id.tvStatus)
        spnPrinters = findViewById(R.id.spnPrinters)
        btnPrint = findViewById(R.id.btnPrint)
        btnCancel = findViewById(R.id.btnCancel)
        btnOpenExternal = findViewById(R.id.btnOpenExternal)
        pbLoading = findViewById(R.id.pbLoading)
        cbIncludePrefix = findViewById(R.id.cbIncludePrefix)

        val logoFile = File(filesDir, DashboardActivity.LOGO_FILE_NAME)
        cbIncludePrefix.visibility = if (logoFile.exists()) View.VISIBLE else View.GONE

        findViewById<View>(R.id.btnManagePrinters).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnCancel.setOnClickListener { finish() }

        btnOpenExternal.setOnClickListener {
            currentPdfUri?.let { uri ->
                openWithExternalViewer(uri)
            } ?: run {
                Toast.makeText(this, getString(R.string.no_pdf_to_open), Toast.LENGTH_SHORT).show()
            }
        }

        btnPrint.setOnClickListener {
            if (isCurrentlyPrinting) return@setOnClickListener

            val selectedIndex = spnPrinters.selectedItemPosition
            if (selectedIndex in savedPrinters.indices && renderedBitmap != null) {
                val target = savedPrinters[selectedIndex]
                prefs.edit().putString("last_used_printer_mac", target.address).apply()

                totalCopiesCount = prefs.getInt(DashboardActivity.KEY_COPIES, 1)
                val includeLogo = cbIncludePrefix.isChecked && logoFile.exists()
                val isManualConfirm = prefs.getBoolean(DashboardActivity.KEY_MANUAL_COPY_CONFIRM, true)

                ensureBluetoothEnabled {
                    if (isManualConfirm) {
                        printSingleCopyManual(target.address, renderedBitmap!!, includeLogo)
                    } else {
                        printReceiptAuto(target.address, renderedBitmap!!, totalCopiesCount, includeLogo)
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.printer_or_pdf_not_ready), Toast.LENGTH_SHORT).show()
            }
        }

        handleIncomingIntent(intent)
    }

    private fun ensureBluetoothEnabled(onReady: () -> Unit) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            Toast.makeText(this, getString(R.string.bt_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            pendingPrintAction = onReady
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        } else {
            onReady()
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrinters()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type == "application/pdf") {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let {
                currentPdfUri = it
                processPdf(it)
            }
        } else if (Intent.ACTION_VIEW == action && type == "application/pdf") {
            intent.data?.let {
                currentPdfUri = it
                processPdf(it)
            }
        } else {
            tvStatus.text = getString(R.string.waiting_for_pdf)
        }
    }

    private fun processPdf(uri: Uri) {
        try {
            val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: run {
                tvStatus.text = getString(R.string.pdf_read_failed)
                return
            }

            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) {
                tvStatus.text = getString(R.string.empty_pdf)
                renderer.close()
                pfd.close()
                return
            }

            val page = renderer.openPage(0)
            val targetWidth = 576
            val initialHeight = (targetWidth.toFloat() / page.width * page.height).toInt()

            val rawBitmap = Bitmap.createBitmap(targetWidth, initialHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rawBitmap)
            canvas.drawColor(Color.WHITE)
            page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

            page.close()
            renderer.close()
            pfd.close()

            val cropped = cropBottomWhiteMargin(rawBitmap)

            renderedBitmap = cropped
            tvStatus.text = getString(R.string.receipt_ready, cropped.width, cropped.height)
            btnPrint.isEnabled = savedPrinters.isNotEmpty()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_prefix, e.message ?: "")
        }
    }

    private fun openWithExternalViewer(uri: Uri) {
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                0
            }

            val resolveInfos = packageManager.queryIntentActivities(viewIntent, flag)
            val apps = resolveInfos.filter { it.activityInfo.packageName != packageName }

            if (apps.isEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.open_or_share_pdf)))
                finish()
                return
            }

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_picker, null)
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val rvPdfApps = dialogView.findViewById<RecyclerView>(R.id.rvPdfApps)
            rvPdfApps.layoutManager = LinearLayoutManager(this)
            rvPdfApps.adapter = PdfAppAdapter(apps, packageManager) { selectedApp ->
                dialog.dismiss()
                val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    setClassName(selectedApp.activityInfo.packageName, selectedApp.activityInfo.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
                finish()
            }

            dialogView.findViewById<Button>(R.id.btnDismissPicker).setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()

            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.app_list_open_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cropBottomWhiteMargin(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var lastContentY = height - 1

        outer@ for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val bl = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * bl).toInt()

                if (luminance < 240) {
                    lastContentY = y
                    break@outer
                }
            }
        }

        val newHeight = Math.min(height, lastContentY + 70)
        return Bitmap.createBitmap(bitmap, 0, 0, width, newHeight)
    }

    private fun loadPrinters() {
        val json = prefs.getString(SettingsActivity.KEY_SAVED_PRINTERS_JSON, null)
        savedPrinters.clear()

        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<SavedPrinter>>() {}.type
            savedPrinters.addAll(Gson().fromJson(json, type))
        }

        if (savedPrinters.isEmpty()) {
            spnPrinters.adapter = ArrayAdapter(
                this,
                R.layout.item_spinner_selected,
                listOf(getString(R.string.no_printer_found_spinner))
            )
            btnPrint.isEnabled = false
            return
        }

        val displayList = savedPrinters.map { "${it.customName} (${it.address})" }
        val spinnerAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, displayList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spnPrinters.adapter = spinnerAdapter

        val lastUsedMac = prefs.getString("last_used_printer_mac", null)
        val lastIndex = savedPrinters.indexOfFirst { it.address == lastUsedMac }
        if (lastIndex != -1) spnPrinters.setSelection(lastIndex)

        btnPrint.isEnabled = renderedBitmap != null
    }

    private fun connectWithRetry(
        device: BluetoothDevice,
        maxAttempts: Int = 3,
        onStatusUpdate: (String) -> Unit
    ): BluetoothSocket {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    onStatusUpdate(getString(R.string.reconnecting_attempt, attempt, maxAttempts))
                }
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                return socket
            } catch (e: Exception) {
                lastException = e
                Thread.sleep((attempt * 800).toLong())
            }
        }
        throw lastException ?: Exception(getString(R.string.printer_connect_failed_check))
    }

    private fun printSingleCopyManual(macAddress: String, receiptBitmap: Bitmap, includeLogo: Boolean) {
        isCurrentlyPrinting = true
        btnPrint.isEnabled = false
        btnCancel.isEnabled = false
        pbLoading.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.printing_copy_status, currentManualCopy, totalCopiesCount)

        thread {
            var socket: BluetoothSocket? = null
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = bluetoothManager.adapter?.getRemoteDevice(macAddress) ?: return@thread

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return@thread

                socket = connectWithRetry(device, maxAttempts = 3) { retryMsg ->
                    runOnUiThread { tvStatus.text = retryMsg }
                }

                val os = socket.outputStream
                sendJobToPrinter(os, receiptBitmap, includeLogo)

                Thread.sleep(1400)

                runOnUiThread {
                    pbLoading.visibility = View.GONE
                    isCurrentlyPrinting = false
                    btnCancel.isEnabled = true

                    if (currentManualCopy < totalCopiesCount) {
                        currentManualCopy++
                        tvStatus.text = getString(R.string.copy_done_rip_and_press, currentManualCopy - 1)
                        btnPrint.text = getString(R.string.btn_print_next_copy, currentManualCopy)
                        btnPrint.isEnabled = true
                    } else {
                        Toast.makeText(this, getString(R.string.all_copies_printed), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.error_prefix, e.message ?: "")
                    pbLoading.visibility = View.GONE
                    btnPrint.isEnabled = true
                    btnCancel.isEnabled = true
                    isCurrentlyPrinting = false
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun printReceiptAuto(macAddress: String, receiptBitmap: Bitmap, copies: Int, includeLogo: Boolean) {
        isCurrentlyPrinting = true
        runOnUiThread {
            btnPrint.isEnabled = false
            btnCancel.isEnabled = false
            pbLoading.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.connecting_to_printer_copies, copies)
        }

        thread {
            var socket: BluetoothSocket? = null
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = bluetoothManager.adapter?.getRemoteDevice(macAddress) ?: return@thread

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return@thread

                socket = connectWithRetry(device, maxAttempts = 3) { retryMsg ->
                    runOnUiThread { tvStatus.text = retryMsg }
                }

                val os = socket.outputStream

                val totalPixelHeight = receiptBitmap.height + 150
                val mechanicalWaitMs = ((totalPixelHeight / 130f) * 1000).toLong().coerceIn(3500L, 8000L)

                val waitSeconds = prefs.getInt(DashboardActivity.KEY_AUTO_WAIT_SECONDS, 4)

                for (copyIndex in 1..copies) {
                    runOnUiThread { tvStatus.text = getString(R.string.printing_copy_status, copyIndex, copies) }

                    sendJobToPrinter(os, receiptBitmap, includeLogo)

                    runOnUiThread { tvStatus.text = getString(R.string.paper_ejecting_wait) }
                    Thread.sleep(mechanicalWaitMs)

                    if (copyIndex < copies) {
                        for (sec in waitSeconds downTo 1) {
                            runOnUiThread {
                                tvStatus.text = getString(R.string.copy_done_countdown, copyIndex, sec)
                            }
                            Thread.sleep(1000)
                        }
                    }
                }

                runOnUiThread {
                    Toast.makeText(this, getString(R.string.multiple_receipts_printed, copies), Toast.LENGTH_SHORT).show()
                    isCurrentlyPrinting = false
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.error_prefix, e.message ?: "")
                    pbLoading.visibility = View.GONE
                    btnPrint.isEnabled = true
                    btnCancel.isEnabled = true
                    isCurrentlyPrinting = false
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendJobToPrinter(outputStream: java.io.OutputStream, receiptBitmap: Bitmap, includeLogo: Boolean) {
        outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ Reset
        outputStream.flush()
        Thread.sleep(60)

        outputStream.write(byteArrayOf(0x1B, 0x33, 24)) // ESC 3 24
        outputStream.flush()

        if (includeLogo) {
            val logoFile = File(filesDir, DashboardActivity.LOGO_FILE_NAME)
            if (logoFile.exists()) {
                val src = BitmapFactory.decodeFile(logoFile.absolutePath)
                val targetW = 576
                val targetH = (targetW.toFloat() / src.width * src.height).toInt()
                val flatBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flatBmp)
                canvas.drawColor(Color.WHITE)
                val scaledSrc = Bitmap.createScaledBitmap(src, targetW, targetH, true)
                canvas.drawBitmap(scaledSrc, 0f, 0f, null)

                printRasterRowByRow(applyFloydSteinbergDithering(flatBmp), outputStream)
                outputStream.write(byteArrayOf(0x1B, 0x4A, 30))
                outputStream.flush()
            }
        }

        printRasterRowByRow(applyFloydSteinbergDithering(receiptBitmap), outputStream)

        val extraLines = prefs.getInt(DashboardActivity.KEY_EXTRA_FEED_LINES, 3)
        val totalFeed = (3 + extraLines).coerceIn(1, 20).toByte()

        outputStream.write(byteArrayOf(0x1B, 0x32)) // ESC 2
        outputStream.write(byteArrayOf(0x1B, 0x64, totalFeed)) // ESC d n
        outputStream.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B 0
        outputStream.flush()
    }

    private fun applyFloydSteinbergDithering(input: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val gray = Array(height) { FloatArray(width) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = input.getPixel(x, y)
                val alpha = (pixel shr 24) and 0xFF
                if (alpha < 50) {
                    gray[y][x] = 255f
                } else {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    gray[y][x] = 0.299f * r + 0.587f * g + 0.114f * b
                }
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val oldVal = gray[y][x]
                val newVal = if (oldVal < 180f) 0f else 255f
                val error = oldVal - newVal

                val finalColor = if (newVal == 0f) Color.BLACK else Color.WHITE
                output.setPixel(x, y, finalColor)

                if (x + 1 < width) gray[y][x + 1] += error * (7f / 16f)
                if (y + 1 < height) {
                    if (x - 1 >= 0) gray[y + 1][x - 1] += error * (3f / 16f)
                    gray[y + 1][x] += error * (5f / 16f)
                    if (x + 1 < width) gray[y + 1][x + 1] += error * (1f / 16f)
                }
            }
        }

        return output
    }

    private fun printRasterRowByRow(bitmap: Bitmap, outputStream: java.io.OutputStream) {
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
            Thread.sleep(12) // MTP 320 tamponunu yormamak icin dengeli gecikme
            y += 24
        }
    }
}

// Ozel Liste Adaptoru
class PdfAppAdapter(
    private val apps: List<ResolveInfo>,
    private val packageManager: PackageManager,
    private val onAppSelected: (ResolveInfo) -> Unit
) : RecyclerView.Adapter<PdfAppAdapter.AppViewHolder>() {

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvAppPackage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = apps[position]
        holder.tvName.text = item.loadLabel(packageManager)
        holder.tvPackage.text = item.activityInfo.packageName
        holder.ivIcon.setImageDrawable(item.loadIcon(packageManager))

        holder.itemView.setOnClickListener {
            onAppSelected(item)
        }
    }

    override fun getItemCount() = apps.size
}