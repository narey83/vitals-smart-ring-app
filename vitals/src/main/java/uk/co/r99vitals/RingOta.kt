package uk.co.r99vitals

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jieli.jl_bt_ota.constant.StateCode
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager
import com.jieli.jl_bt_ota.interfaces.BtEventCallback
import com.jieli.jl_bt_ota.interfaces.IUpgradeCallback
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure
import com.jieli.jl_bt_ota.model.base.BaseError
import java.util.UUID

/**
 * Flashes the ring's firmware over BLE.
 *
 * The R99 is a JieLi AC632N, and its OTA is JieLi's authenticated RCSP protocol — not a sequence
 * of frames this app could send itself. So the flash is driven by JieLi's own `jl_bt_ota` library
 * (see vitals/libs and PROTOCOL.md's "Updating the firmware"); this class is the thin bridge the
 * library needs: it owns a BLE link to the ring and hands the library a way to send bytes and be
 * fed the ones that come back. It is modelled directly on the vendor app's own integration
 * (`JLOTAManager`), which is the only worked example of this ring being updated.
 *
 * **This drives a firmware write and can brick the ring if the link is wrong.** It runs on its own
 * connection and must have the ring to itself: stop [CollectorService] first, and keep the phone
 * beside the ring throughout. It has not been proven against the hardware yet — see the caution in
 * the update flow before it is ever run for real.
 *
 * The RCSP channel is the `ae00` service: writes go to `ae01`, the ring answers on `ae02` — the
 * "authentication handshake" characteristics in PROTOCOL.md, which are in fact this OTA transport.
 */
class RingOta(
    context: Context,
    private val address: String,
    private val ufwPath: String,
    private val listener: Listener,
) : BluetoothOTAManager(context) {

    /** What the flow reports back to whoever started it. All calls are on the main thread. */
    interface Listener {
        /** 0..100, as the library sends progress. */
        fun onProgress(percent: Int)
        /** The ring has rebooted into its loader and is being picked up again — see onNeedReconnect. */
        fun onReconnecting()
        fun onSuccess()
        fun onFailure(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null

    /** The negotiated payload size; writes to [WRITE] are cut to this. Set once the MTU is granted. */
    private var chunk = DEFAULT_CHUNK
    private var finished = false

    init {
        // The same options the vendor app sets, which is the only combination known to work on
        // this ring: no separate auth device, the library paces its own writes, and it keeps the
        // link across the ring's mid-flash reboot rather than expecting the caller to.
        val options = BluetoothOTAConfigure.createDefault()
            .setPriority(0)
            .setUseAuthDevice(false)
            .setBleIntervalMs(500)
            .setTimeoutMs(3000)
            .setMtu(517)
            .setNeedChangeMtu(false)
            .setUseReconnect(true)
            .setFirmwareFilePath(ufwPath)
        configure(options)
        // When the library has finished its RCSP handshake over the link below, it reports the
        // connection ready here; that is the moment to start the flash.
        registerBluetoothCallback(object : BtEventCallback() {
            override fun onConnection(dev: BluetoothDevice?, status: Int) {
                if (status == StateCode.CONNECTION_OK) beginUpgrade()
            }
        })
    }

    /** Opens the OTA link. Everything else follows from the connection callbacks. */
    @SuppressLint("MissingPermission")
    fun start() {
        val target = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return fail("$address is not a usable address")
        device = target
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        finished = true
        runCatching { cancelOTA() }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        runCatching { release() }
    }

    private fun beginUpgrade() {
        getBluetoothOption().setFirmwareFilePath(ufwPath)
        startOTA(object : IUpgradeCallback {
            override fun onStartOTA() {}
            override fun onProgress(type: Int, progress: Float) =
                listener.onProgress((progress * 100).toInt())
            override fun onNeedReconnect(addr: String?, reconnect: Boolean) {
                // The ring reboots into its loader partway through and comes back advertising at
                // the next address up. Pick it up there and hand the library the fresh link; it
                // carries on from where it was. (The vendor app does exactly this, MAC + 1.)
                listener.onReconnecting()
                reconnectAt(macPlusOne(addr ?: address))
            }
            override fun onStopOTA() { finished = true; listener.onSuccess() }
            override fun onCancelOTA() { fail("update cancelled") }
            override fun onError(error: BaseError?) = fail(error?.message ?: "update failed")
        })
    }

    @SuppressLint("MissingPermission")
    private fun reconnectAt(newAddress: String) {
        runCatching { gatt?.close() }
        val target = runCatching { adapter.getRemoteDevice(newAddress) }.getOrNull()
            ?: return fail("$newAddress is not a usable address")
        device = target
        handler.postDelayed({ gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }, 1000)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        handler.post { listener.onFailure(message) }
        stop()
    }

    // ---- The link the library drives ---------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(517)
            } else {
                device?.let { onBtDeviceConnection(it, StateCode.CONNECTION_DISCONNECT) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            chunk = (mtu - 3).coerceAtLeast(DEFAULT_CHUNK)
            getBluetoothOption().setMtu(mtu)
            device?.let { this@RingOta.onMtuChanged(g, mtu, status) }
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: return fail("the ring is not exposing its update channel")
            val notify = service.getCharacteristic(NOTIFY) ?: return fail("the ring is not exposing its update channel")
            if (service.getCharacteristic(WRITE) == null) return fail("the update channel has no write endpoint")
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CLIENT_CONFIG) ?: return fail("update channel has no config descriptor")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Subscribed to ae02: the link is ready, so tell the library it is connected and let
            // it run its handshake. The BtEventCallback above fires when that succeeds.
            if (descriptor.characteristic.uuid == NOTIFY) {
                device?.let { onBtDeviceConnection(it, StateCode.CONNECTION_OK) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == WRITE) { writing = false; handler.post { pump() } }
        }

        @Deprecated("Superseded on Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = ch.value ?: return
            if (ch.uuid == NOTIFY) device?.let { onReceiveDeviceData(it, value) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == NOTIFY) device?.let { onReceiveDeviceData(it, value) }
        }
    }

    // ---- IBluetoothManager: what the library calls on us -------------------------------------

    override fun getConnectedDevice(): BluetoothDevice? = device
    override fun getConnectedBluetoothGatt(): BluetoothGatt? = gatt

    @SuppressLint("MissingPermission")
    override fun connectBluetoothDevice(dev: BluetoothDevice) {
        device = dev
        gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    override fun disconnectBluetoothDevice(dev: BluetoothDevice) {
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    /**
     * One RCSP packet from the library, cut to the MTU and written to [WRITE] in order. Writes are
     * sent one at a time — each waits for the last to report back in [pump] — because Android
     * carries a single outstanding write per connection and silently drops any sent over a busy
     * one, which mid-flash would corrupt the image.
     */
    @Synchronized
    override fun sendDataToDevice(dev: BluetoothDevice, data: ByteArray?): Boolean {
        if (data == null) return false
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunk, data.size)
            pending.addLast(data.copyOfRange(offset, end))
            offset = end
        }
        handler.post { pump() }
        return true
    }

    private val pending = ArrayDeque<ByteArray>()
    private var writing = false

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val write = g.getService(SERVICE)?.getCharacteristic(WRITE) ?: return
        val frame = pending.firstOrNull() ?: return
        writing = true
        // Match the endpoint: RCSP writes go with a response where ae01 supports it, and without
        // where it only offers write-no-response. Either way onCharacteristicWrite reports back and
        // paces the next chunk, so a mismatch here would otherwise stall the flash mid-image.
        val type = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(write, frame, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") write.value = frame
            @Suppress("DEPRECATION") write.writeType = type
            @Suppress("DEPRECATION") g.writeCharacteristic(write)
        }
        if (ok) {
            // Sent. onCharacteristicWrite removes it from flight and pumps the next; the library's
            // own 3 s timeout fails the OTA cleanly if a write is never acknowledged.
            pending.removeFirst()
        } else {
            // Radio busy — leave the frame queued and come back to it rather than dropping it.
            writing = false
            handler.postDelayed({ pump() }, 20)
        }
    }

    private fun macPlusOne(mac: String): String {
        val bytes = mac.split(":").map { it.toInt(16) }.toMutableList()
        var i = bytes.size - 1
        while (i >= 0) { bytes[i] = (bytes[i] + 1) and 0xFF; if (bytes[i] != 0) break; i-- }
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEFAULT_CHUNK = 20
    }
}
