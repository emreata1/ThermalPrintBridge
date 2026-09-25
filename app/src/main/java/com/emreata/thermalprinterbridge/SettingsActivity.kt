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
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class SettingsActivity : AppCompatActivity() {

    private lateinit var rvPrinters: RecyclerView
    private lateinit var prefs: SharedPreferences
    private val printerList = mutableListOf<SavedPrinter>()
    private val gson = Gson()
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    companion object {
        const val PREFS_NAME = "ThermalPrinterPrefs"
        const val KEY_SAVED_PRINTERS_JSON = "saved_printers_json"
    }

    // Bluetooth açıldıktan sonra tetiklenecek eylem
    private var pendingBluetoothAction: (() -> Unit)? = null

    // Bluetooth açma Intent fırlatıcısı
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingBluetoothAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.bt_required_action), Toast.LENGTH_SHORT).show()
        }
        pendingBluetoothAction = null
    }

    // Android 12+ Bluetooth izin fırlatıcısı
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ensureBluetoothEnabled { showDeviceDiscoveryDialog() }
        } else {
            Toast.makeText(this, getString(R.string.bt_permission_list_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        rvPrinters = findViewById(R.id.rvPrinters)
        rvPrinters.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        loadSavedPrinters()

        findViewById<View>(R.id.btnAddNewPrinter).setOnClickListener {
            checkAndStartDiscovery()
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

    private fun checkAndStartDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        ensureBluetoothEnabled {
            showDeviceDiscoveryDialog()
        }
    }

    private fun loadSavedPrinters() {
        val json = prefs.getString(KEY_SAVED_PRINTERS_JSON, null)
        printerList.clear()
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<SavedPrinter>>() {}.type
                printerList.addAll(gson.fromJson(json, type))
            } catch (_: Exception) {
                prefs.edit().remove(KEY_SAVED_PRINTERS_JSON).apply()
            }
        }
        rvPrinters.adapter = PrinterAdapter()
    }

    private fun savePrinters() {
        val json = gson.toJson(printerList)
        prefs.edit().putString(KEY_SAVED_PRINTERS_JSON, json).apply()
        rvPrinters.adapter?.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceDiscoveryDialog() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_bonded_devices_msg), Toast.LENGTH_LONG).show()
            return
        }

        val defaultDevName = getString(R.string.default_device_name)
        val labels = bondedDevices.map { "${it.name ?: defaultDevName} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_select_paired_printer))
            .setItems(labels) { _, which ->
                val selectedDev = bondedDevices[which]
                showNamePromptDialog(selectedDev)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showNamePromptDialog(device: BluetoothDevice) {
        val defaultDevName = getString(R.string.default_device_name)
        val defaultPrinterName = getString(R.string.default_thermal_printer_name)

        val input = EditText(this)
        input.hint = getString(R.string.printer_name_hint)
        input.setText(device.name ?: defaultPrinterName)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_name_printer))
            .setView(input)
            .setPositiveButton(getString(R.string.dialog_add)) { _, _ ->
                val name = input.text.toString().trim()
                val customName = if (name.isEmpty()) (device.name ?: defaultDevName) else name

                val existing = printerList.indexOfFirst { it.address == device.address }
                if (existing != -1) {
                    printerList[existing].customName = customName
                } else {
                    printerList.add(SavedPrinter(device.address, device.name ?: defaultDevName, customName))
                }
                savePrinters()
                Toast.makeText(this, getString(R.string.printer_added_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
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
        throw lastException ?: Exception(getString(R.string.printer_connect_failed_check))
    }

    @SuppressLint("MissingPermission")
    private fun testPrint(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }

        ensureBluetoothEnabled {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter?.getRemoteDevice(address) ?: return@ensureBluetoothEnabled

            Toast.makeText(this, getString(R.string.sending_test_print), Toast.LENGTH_SHORT).show()

            thread {
                var socket: BluetoothSocket? = null
                try {
                    socket = connectWithRetry(device, maxAttempts = 3)

                    val os = socket.outputStream
                    os.write(byteArrayOf(0x1B, 0x40)) // Reset

                    val isTurkish = LocaleHelper.getCurrentLocale().startsWith("tr")
                    val statusText = if (isTurkish) "       BAGLANTI BASARILI!" else "       CONNECTION OK!"
                    val text = "\n================================\n     THERMAL PRINT BRIDGE\n$statusText\n================================\n\n\n".toByteArray(Charsets.ISO_8859_1)
                    os.write(text)

                    val extraLines = prefs.getInt(DashboardActivity.KEY_EXTRA_FEED_LINES, 3)
                    val totalFeed = (3 + extraLines).coerceIn(1, 20).toByte()
                    os.write(byteArrayOf(0x1B, 0x64, totalFeed)) // Dinamik besleme
                    os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // Kes
                    os.flush()

                    runOnUiThread { Toast.makeText(this, getString(R.string.test_success), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, getString(R.string.error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show() }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    inner class PrinterAdapter : RecyclerView.Adapter<PrinterAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvCustom: TextView = v.findViewById(R.id.tvCustomName)
            val tvReal: TextView = v.findViewById(R.id.tvRealInfo)
            val btnTest: View = v.findViewById(R.id.btnTestPrint)
            val btnDel: ImageButton = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_printer, parent, false)
            return VH(v)
        }

        override fun getItemCount() = printerList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = printerList[position]
            holder.tvCustom.text = p.customName
            holder.tvReal.text = "${p.realName} • ${p.address}"

            holder.btnTest.setOnClickListener { testPrint(p.address) }
            holder.btnDel.setOnClickListener {
                printerList.removeAt(position)
                savePrinters()
            }
        }
    }
}