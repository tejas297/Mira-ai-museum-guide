package ai.mira.guide.beacon

import ai.mira.guide.data.Artifact
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

data class Sighting(val minor: Int, val rssi: Int, val atMs: Long)

/**
 * Which beacons the panel lists. Simulated mode shows every configured
 * stop (it is a tour you can preview). Real mode shows only beacons the
 * radio has actually heard from recently — listing silent beacons would
 * pretend coverage that is not there.
 */
fun visibleBeaconMinors(
    realMode: Boolean,
    all: Collection<Int>,
    sighted: Collection<Int>
): Set<Int> =
    if (realMode) all.filter { it in sighted }.toSet() else all.toSet()

/**
 * Single seam between simulated and real beacons. The mock below and a future
 * BluetoothLeScanner implementation speak the same sightings stream.
 */
interface BeaconScanner {
    fun start(onSightings: (List<Sighting>) -> Unit)
    fun stop()
}

/** Emits fake sightings for the stop the visitor "stands at". */
class MockBeaconScanner(private val artifacts: List<Artifact>) : BeaconScanner {
    @Volatile var position: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var callback: ((List<Sighting>) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val list = artifacts.mapIndexed { i, art ->
                val dist = abs(i - position)
                val jitter = ((now / 700 + i * 2) % 3).toInt() - 1
                val rssi = when (dist) {
                    0 -> -50 - (now % 3).toInt()
                    1 -> -78 + jitter
                    else -> -92 + jitter
                }
                Sighting(art.minor, rssi, now)
            }
            callback?.invoke(list)
            handler.postDelayed(this, 700)
        }
    }

    override fun start(onSightings: (List<Sighting>) -> Unit) {
        callback = onSightings
        running = true
        handler.post(tick)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}

/**
 * Real BLE scanner: listens for iBeacon advertisements (e.g. a second phone
 * running nRF Connect's beacon simulator) with the venue UUID, and emits the
 * same sightings stream as the mock. Permissions are checked by the caller.
 */
class BleBeaconScanner(
    private val context: Context,
    private val venueUuid: String = "B9407F30-F5F8-466E-AFF9-25556B57FE6D"
) : BeaconScanner {
    private val handler = Handler(Looper.getMainLooper())
    private val latest = mutableMapOf<Int, Sighting>()
    private var callback: ((List<Sighting>) -> Unit)? = null
    private var emitting = false

    private val emitTick = object : Runnable {
        override fun run() {
            if (!emitting) return
            val cutoff = System.currentTimeMillis() - 6000
            latest.entries.removeAll { it.value.atMs < cutoff }
            callback?.invoke(latest.values.toList())
            handler.postDelayed(this, 700)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.getManufacturerSpecificData(0x004C) ?: return
            if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return
            val uuid = uuidString(data.copyOfRange(2, 18))
            if (!uuid.equals(venueUuid, ignoreCase = true)) return
            val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            latest[minor] = Sighting(minor, result.rssi, System.currentTimeMillis())
        }
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.Manifest.permission.BLUETOOTH_SCAN
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, perm
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun start(onSightings: (List<Sighting>) -> Unit) {
        callback = onSightings
        // Permission may be revoked after the caller checked: never crash.
        if (!hasScanPermission()) return
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            return
        }
        emitting = true
        handler.post(emitTick)
    }

    override fun stop() {
        emitting = false
        handler.removeCallbacks(emitTick)
        try {
            val perm = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.Manifest.permission.BLUETOOTH_SCAN
            } else {
                android.Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, perm
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            // Bluetooth off or permission revoked mid-scan: stopping is best-effort.
        }
    }

    private fun uuidString(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}

/**
 * Picks the nearest artifact from sightings. A challenger must stay strongest
 * for [debounceMs] before it steals focus, so cards do not flap.
 */
class NearestResolver(private val debounceMs: Long = 1500) {
    var currentMinor: Int? = null
        private set
    private var challenger: Int? = null
    private var challengerSince: Long = 0

    fun reset() {
        currentMinor = null
        challenger = null
    }

    /** Returns the artifact minor to show now, or null to keep the current one. */
    fun resolve(sightings: List<Sighting>, nowMs: Long): Int? {
        val best = sightings.maxByOrNull { it.rssi } ?: return null
        if (currentMinor == null) {
            currentMinor = best.minor
            return best.minor
        }
        if (best.minor == currentMinor) {
            challenger = null
            return null
        }
        if (challenger != best.minor) {
            challenger = best.minor
            challengerSince = nowMs
            return null
        }
        if (nowMs - challengerSince >= debounceMs) {
            currentMinor = best.minor
            challenger = null
            return best.minor
        }
        return null
    }
}
