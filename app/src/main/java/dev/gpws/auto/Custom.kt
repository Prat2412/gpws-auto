package dev.gpws.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Your own sounds: any audio or video file (a screen recording works), trimmed to the callout and
 * kept as files/custom/<sound>.wav, which [Voice] plays instead of the built-in clip.
 * They stay on this phone; nothing here is part of the app itself.
 */
object Custom {

    const val RATE = 22050
    private const val MAX_SECONDS = 90

    fun file(ctx: Context, sound: String) = File(ctx.filesDir, "custom/$sound.wav")
    fun has(ctx: Context, sound: String) = file(ctx, sound).exists()
    fun reset(ctx: Context, sound: String) {
        file(ctx, sound).delete()
    }

    /** The first 90 s of the file's audio, as mono floats at [RATE]. Slow: call it off the main thread. */
    fun decode(ctx: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no sound in that file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(ex, codec, format)
            } finally {
                codec.release()
            }
        } finally {
            ex.release()
        }
    }

    private fun drain(ex: MediaExtractor, codec: MediaCodec, format: MediaFormat): FloatArray {
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var out = FloatArray(rate * 10)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0 || ex.sampleTime > MAX_SECONDS * 1_000_000L) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val frames = info.size / (channels * if (float) 4 else 2)
                if (n + frames > out.size) out = out.copyOf(max(out.size * 2, n + frames))
                if (float) {
                    val fb = buf.asFloatBuffer()
                    repeat(frames) { var v = 0f; repeat(channels) { v += fb.get() }; out[n++] = v / channels }
                } else {
                    val sb = buf.asShortBuffer()
                    repeat(frames) { var v = 0f; repeat(channels) { v += sb.get() }; out[n++] = v / channels / 32768f }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return resample(out, n, rate)
    }

    private fun resample(x: FloatArray, n: Int, from: Int): FloatArray {
        if (from == RATE) return x.copyOf(n)
        val r = from.toDouble() / RATE
        val out = FloatArray((n / r).toInt())
        for (i in out.indices) {
            val c = i * r
            if (r <= 1) {  // upsampling: straight-line between neighbours
                val a = c.toInt()
                val b = min(n - 1, a + 1)
                out[i] = x[a] + (x[b] - x[a]) * (c - a).toFloat()
            } else {       // downsampling: average the samples this one stands for, so nothing aliases
                val a = max(0, (c - r / 2).toInt())
                val b = min(n - 1, (c + r / 2).toInt())
                var sum = 0f
                for (j in a..b) sum += x[j]
                out[i] = sum / (b - a + 1)
            }
        }
        return out
    }

    /** Where the sound in [s] starts and ends: first and last samples above 5% of the peak, plus 50 ms. */
    fun bounds(s: FloatArray): Pair<Int, Int> {
        var peak = 0f
        for (v in s) peak = max(peak, abs(v))
        if (peak == 0f) return 0 to s.size
        val first = s.indexOfFirst { abs(it) > peak * 0.05f }
        val last = s.indexOfLast { abs(it) > peak * 0.05f }
        return max(0, first - RATE / 20) to min(s.size, last + RATE / 20)
    }

    /** s[from until to], peaking at -1 dBFS like the built-in clips, with 5 ms fades: 16-bit PCM. */
    private fun pcm(s: FloatArray, from: Int, to: Int): ShortArray {
        val len = to - from
        var peak = 1e-6f
        for (i in from until to) peak = max(peak, abs(s[i]))
        val gain = 0.89f / peak
        val fade = min(len / 2, RATE / 200)
        return ShortArray(len) { i ->
            var v = s[from + i] * gain
            if (i < fade) v *= i / fade.toFloat()
            if (i >= len - fade) v *= (len - 1 - i) / fade.toFloat()
            (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }

    fun save(ctx: Context, sound: String, s: FloatArray, from: Int, to: Int) {
        val pcm = pcm(s, from, to)
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        val f = file(ctx, sound)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(b.array())
        tmp.renameTo(f)
    }

    // ---- Sound packs: every custom sound in one zip, to share with friends ----

    private val NAME = Regex("""[a-z0-9_]{1,40}\.wav""")
    private const val MANIFEST = "gpws-sounds.txt"

    private fun mine(ctx: Context) =
        File(ctx.filesDir, "custom").listFiles { f -> NAME.matches(f.name) }?.sortedBy { it.name } ?: emptyList()

    fun count(ctx: Context) = mine(ctx).size

    /** Zips every custom sound, with a list of what each one is. Null if there are none. */
    fun export(ctx: Context, labels: Map<String, String>): File? {
        val files = mine(ctx)
        if (files.isEmpty()) return null
        val out = File(ctx.cacheDir, "share/${ShareProvider.NAME}")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            val list = files.joinToString("") { f -> f.name.removeSuffix(".wav").let { "$it = ${labels[it] ?: it}\n" } }
            zip.write("GPWS Auto sound pack\n\n$list".toByteArray())
            zip.closeEntry()
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** Adds the sounds from a shared pack, replacing any with the same name. Returns how many. */
    fun import(ctx: Context, uri: Uri): Int {
        val dir = File(ctx.filesDir, "custom").apply { mkdirs() }
        var n = 0
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("can't open that file")
        ZipInputStream(input).use { zip ->
            while (n < 100) {
                val e = zip.nextEntry ?: break
                // Only plainly named WAVs at the top level: nothing can land outside our folder.
                if (e.isDirectory || !NAME.matches(e.name)) continue
                val bytes = read(zip, 4 shl 20) ?: continue
                if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) continue
                val f = File(dir, e.name)
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                n++
            }
        }
        if (n == 0) throw IOException("no GPWS sounds in that file")
        return n
    }

    /** The rest of the current zip entry, or null if it's bigger than [max] bytes. */
    private fun read(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) return out.toByteArray()
            if (out.size() + r > max) return null
            out.write(buf, 0, r)
        }
    }

    // ---- Preview while trimming ----

    private var track: AudioTrack? = null
    private var trackFrom = 0

    fun preview(s: FloatArray, from: Int, to: Int) {
        stopPreview()
        val pcm = pcm(s, from, to)
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setNotificationMarkerPosition(pcm.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = stopPreview()
            override fun onPeriodicNotification(track: AudioTrack) {}
        })
        t.play()
        track = t
        trackFrom = from
    }

    /** The sample the preview is playing, for the trimmer's playhead; null when quiet. */
    fun playhead(): Int? = track?.let { trackFrom + it.playbackHeadPosition }

    fun stopPreview() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (e: IllegalStateException) {
            // already stopped
        }
        t.release()
    }
}
