package dev.gpws.auto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log on the main screen, plus a drive log file that survives the app being closed:
 * every event and every distinct Maps notification, so a drive can be replayed afterwards with
 * `adb shell run-as dev.gpws.auto cat files/drive_log.txt`. Main thread only.
 */
object Events {

    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    /** What the last Maps notification said, and what we made of it. */
    var maps = "Nothing yet. Start navigating in Google Maps."
        private set

    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, "drive_log.txt")
    }

    fun add(msg: String) {
        Log.i("GPWS", msg)  // also readable over USB: adb logcat -s GPWS
        lines.addFirst("${time.format(Date())}  $msg")
        if (lines.size > 100) lines.removeLast()
        persist(msg)
        changed()
    }

    private var lastPersisted = ""

    fun showMaps(r: NavParser.Reading, extras: String) {
        val verdict = when {
            r.arrived -> "→ arrived"
            r.metres != null -> "→ %.0f m to destination (±%.0f)".format(r.metres, r.resolution / 2)
            else -> "→ no distance to destination found"
        }
        // The file gets every change, including fields we don't parse yet, so a drive can be studied later.
        val record = "MAPS  " + r.lines.joinToString("  ⏎  ") + "  " + verdict + "  {" + extras + "}"
        if (record != lastPersisted) {
            lastPersisted = record
            persist(record)
        }
        val text = r.lines.joinToString("\n") + "\n" + verdict
        if (text == maps) return
        maps = text
        changed()
    }

    fun log() = lines.joinToString("\n")

    private fun persist(line: String) {
        val f = file ?: return
        try {
            if (f.length() > 512 * 1024) f.renameTo(File(f.parentFile, "drive_log.old.txt"))
            f.appendText("${stamp.format(Date())}  $line\n")
        } catch (e: Exception) {
            // Logging must never break the callouts.
        }
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }
}
