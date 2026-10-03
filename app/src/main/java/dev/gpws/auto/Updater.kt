package dev.gpws.auto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Updates from the project's GitHub releases, only when the user taps to check: nothing runs in
 * the background. The newest release's .apk goes to Android's own installer, which asks the user
 * to confirm and refuses anything not signed with the same key as the installed app.
 */
object Updater {

    /** "owner/repo" on GitHub. Blank turns updates off: the app then says they aren't set up. */
    const val REPO = "Prat2412/gpws-auto"

    val ready get() = REPO.isNotBlank()

    class Release(val version: String, val notes: String, val apkUrl: String?, val apkBytes: Long, val page: String)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun installed(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Is release tag "v1.10" newer than "1.9"? Compared number by number. */
    fun newer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until max(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The latest release, or why there isn't one. Answers on the main thread. */
    fun check(done: (Release?, String?) -> Unit) {
        worker.execute {
            val (release, error) = try {
                latest() to null
            } catch (e: Exception) {
                null to explain(e)
            }
            main.post { done(release, error) }
        }
    }

    private fun latest(): Release {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GPWS-Auto")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IllegalStateException("There's no release on GitHub yet.")
                else -> throw IllegalStateException("GitHub answered HTTP $code.")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.optJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
            return Release(
                j.optString("tag_name"),
                j.optString("body"),
                apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://github.com/") },
                apk?.optLong("size") ?: 0L,
                j.optString("html_url", "https://github.com/$REPO/releases/latest"),
            )
        } finally {
            c.disconnect()
        }
    }

    /** Fetches the release's APK into the cache. `progress` gets 0..100; then `done` gets the file or an error. */
    fun download(ctx: Context, r: Release, progress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        val url = r.apkUrl ?: return done(null, "This release has no APK attached.")
        val out = File(ctx.cacheDir, "update.apk")
        worker.execute {
            val (file, error) = try {
                fetch(url, out, r.apkBytes, progress)
                out to null
            } catch (e: Exception) {
                out.delete()
                null to explain(e)
            }
            main.post { done(file, error) }
        }
    }

    private fun fetch(url: String, out: File, expected: Long, progress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection  // GitHub redirects to its file host, https to https
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "GPWS-Auto")
        try {
            if (c.responseCode != 200) throw IllegalStateException("The download failed (HTTP ${c.responseCode}).")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            var got = 0L
            var shown = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else 0
                        if (pct != shown) {
                            shown = pct
                            main.post { progress(pct) }
                        }
                    }
                }
            }
            if (expected > 0 && got != expected) throw IllegalStateException("The download was cut short.")
        } finally {
            c.disconnect()
        }
    }

    /** Hands the APK to Android's installer; [UpdateReceiver] shows its confirm screen. Errors come back on the main thread. */
    fun install(ctx: Context, apk: File, failed: (String) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)  // only ever an update of GPWS Auto itself
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("gpws-auto.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val back = Intent(app, UpdateReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(app, id, back, flags).intentSender)
                }
            } catch (e: Exception) {
                val why = explain(e)
                main.post { failed(why) }
            }
        }
    }

    private fun explain(e: Exception) = when (e) {
        is UnknownHostException -> "No internet connection."
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** Android's installer reporting back: show its confirm screen, or say what went wrong. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Gpws.init(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Events.add("update installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Events.add("update cancelled")
            else -> {
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "it doesn't match this copy (signed with a different key, or older). " +
                            "Uninstall GPWS Auto, then install the new one from GitHub."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status."
                }
                Events.add("update failed: $why")
                Toast.makeText(context, "Update failed: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
