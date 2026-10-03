package dev.gpws.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Are we in the car? Two ways to know:
 *  - Android Auto: asked the way Google's car library (CarConnection) does, a content provider to
 *    query plus a broadcast when it changes.
 *  - Plain Bluetooth, for cars without Android Auto: the car stereo you picked shows up as a
 *    Bluetooth audio output. Android lists those by name without any Bluetooth permission.
 */
object Car {

    private val URI = Uri.parse("content://androidx.car.app.connection")
    private const val COLUMN = "CarConnectionState"  // 0 = not connected, 1 = Android Automotive, 2 = Android Auto
    private const val ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

    /** true = connected to a car, false = not, null = can't tell (no Android Auto installed). */
    fun connected(ctx: Context): Boolean? = try {
        ctx.contentResolver.query(URI, arrayOf(COLUMN), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(COLUMN)) != 0 else null
        }
    } catch (e: Exception) {
        null
    }

    /** Calls [changed] with the new state whenever Android Auto connects or disconnects. */
    fun watch(ctx: Context, changed: (Boolean?) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed(connected(context))
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        return receiver
    }

    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Names of the Bluetooth audio devices connected right now ("Hyundai BT"), for picking your car. */
    fun bluetoothNames(ctx: Context): List<String> =
        audio(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH }
            .map { it.productName.toString().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Is the car stereo you picked connected? False if you haven't picked one. */
    fun bluetoothCar(ctx: Context): Boolean = Gpws.carBluetooth?.let { it in bluetoothNames(ctx) } ?: false

    /** Calls [changed] whenever a Bluetooth audio device comes or goes. */
    fun watchBluetooth(ctx: Context, changed: (Boolean) -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed(bluetoothCar(ctx))
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed(bluetoothCar(ctx))
        }
        audio(ctx).registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unwatchBluetooth(ctx: Context, callback: AudioDeviceCallback) = audio(ctx).unregisterAudioDeviceCallback(callback)
}
