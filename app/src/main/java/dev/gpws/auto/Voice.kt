package dev.gpws.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Plays a callout on the navigation-voice channel, so Android Auto sends it to the car speakers
 * and ducks your music the same way a Maps prompt does. Uses res/raw/<sound> if it exists,
 * otherwise text-to-speech. A new sound cuts off the one playing, like the real thing.
 */
class Voice(
    private val ctx: Context,
    private val duck: () -> Boolean,
    private val boostMb: () -> Int,  // smart volume: extra gain for the next clip, in millibels
) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var waker: MediaPlayer? = null   // the silence that wakes a sleeping Bluetooth link
    private var lastPlayedAt = -WAKE_AFTER_MS
    private var ttsReady = false
    private var pending: String? = null
    private var playing = 0                  // id of the sound currently playing
    private var onDone: (() -> Unit)? = null // runs when that sound finishes on its own
    private val tts = TextToSpeech(ctx, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Events.add("text-to-speech failed to start")
            return
        }
        tts.setLanguage(Locale.US)
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(1.1f)
        tts.setPitch(0.8f)  // flatter and lower: a bit more cockpit, a bit less assistant
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId?.toIntOrNull())
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId?.toIntOrNull())
        })
        ttsReady = true
        pending?.let { speak(it, playing) }
        pending = null
    }

    /** True while a clip or speech is still playing. */
    val busy: Boolean get() = player?.isPlaying == true || waker?.isPlaying == true || (ttsReady && tts.isSpeaking)

    /** Main thread only. [then] runs after the sound finishes, unless something cuts it off. */
    fun play(sound: String, speech: String, then: (() -> Unit)? = null) {
        stop()
        val id = ++playing
        onDone = then
        // Without focus, other apps (Spotify) aren't lowered or told anything; the callout plays over them.
        if (duck()) audio.requestAudioFocus(focus)
        // Your own sound for this callout, if you imported one; else the built-in clip; else speech.
        val custom = Custom.file(ctx, sound)
        if (custom.exists() && playFile(id) { it.setDataSource(custom.path) }) return
        val res = ctx.resources.getIdentifier(sound, "raw", ctx.packageName)
        val builtIn = res != 0 && playFile(id) { mp ->
            ctx.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        }
        if (!builtIn) speak(speech, id)
    }

    /** Silences everything immediately. */
    fun stopAll() {
        stop()
        playing++
        onDone = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun playFile(id: Int, source: (MediaPlayer) -> Unit): Boolean {
        val mp = MediaPlayer()
        return try {
            source(mp)
            mp.setAudioAttributes(attrs)
            mp.setOnCompletionListener {
                if (player === it) release() else it.release()
                finished(id)
            }
            mp.prepare()
            boost(mp)
            val wake = if (needsWake()) wakeUp() else null
            if (wake != null) {
                wake.setNextMediaPlayer(mp)  // the callout follows the silence without a gap
                wake.start()
                waker = wake
            } else {
                mp.start()
            }
            player = mp
            lastPlayedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            mp.release()
            false
        }
    }

    // A Bluetooth car stereo lets its audio link sleep between sounds, and waking it eats the start of
    // whatever plays next: "ten" can vanish entirely. So after a quiet spell over Bluetooth, a quarter
    // second of silence goes first and the callout starts on a link that's already awake.
    private fun needsWake(): Boolean {
        if (SystemClock.elapsedRealtime() - lastPlayedAt < WAKE_AFTER_MS || audio.isMusicActive) return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    private fun wakeUp(): MediaPlayer? {
        val res = ctx.resources.getIdentifier("wake_silence", "raw", ctx.packageName)
        if (res == 0) return null
        val mp = MediaPlayer()
        return try {
            ctx.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setAudioAttributes(attrs)
            mp.setOnCompletionListener {
                it.release()
                if (waker === it) waker = null
            }
            mp.prepare()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    // The clips already peak near full scale, so louder needs a loudness enhancer, not setVolume().
    private fun boost(mp: MediaPlayer) {
        val mb = boostMb()
        if (mb <= 0) return
        enhancer = try {
            LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain(mb)
                enabled = true
            }
        } catch (e: Exception) {
            null  // no effect support on this device: play at normal volume
        }
    }

    private fun release() {
        waker?.release()
        waker = null
        enhancer?.release()
        enhancer = null
        player?.release()
        player = null
    }

    private fun speak(text: String, id: Int) {
        if (!ttsReady) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
    }

    private fun stop() {
        release()
        pending = null
        if (ttsReady) tts.stop()
    }

    private companion object {
        const val WAKE_AFTER_MS = 4000L  // quiet longer than this and the link may have gone to sleep
    }

    // May arrive on a TTS binder thread.
    private fun finished(id: Int?) {
        main.post {
            if (id != playing) return@post  // something newer already took over
            audio.abandonAudioFocusRequest(focus)
            val then = onDone
            onDone = null
            then?.invoke()
        }
    }
}
