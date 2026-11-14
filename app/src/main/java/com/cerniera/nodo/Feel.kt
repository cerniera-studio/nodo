package com.cerniera.nodo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sound and touch (14 Nov 2026). Every sound and buzz comes from something the thread does, and
 * nothing plays at rest:
 * - tick: a fibre catching on a day mark while a knot is dragged; a chip; a knot set down.
 * - crease: a knot reaching the crease, either way. The hinge: low and firm.
 * - cinch: a knot pulled tight (tied from the loose end, or done). Thread drawn through itself, then seating.
 * - slip: a knot let go (untied, undone, left empty). The thread sliding loose.
 * - pluck: the thread tapped, or let go after a stretch. Its note rises with how taut it is.
 *
 * Sounds are synthesised once on first use (the same recipes as the browser preview), written as
 * short WAVs and played through SoundPool on the sonification stream, so they stay quiet when the
 * phone is on silent or vibrate. Haptics use the composition primitives where the phone has them.
 */
class Feel private constructor(private val c: Context) {
    private val vib: Vibrator? = c.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val audio = c.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = HashMap<String, Int>()
    @Volatile private var ready = false

    init {
        Thread {
            runCatching {
                val dir = File(c.cacheDir, "feel-v1").apply { mkdirs() }
                val sounds = mapOf("tick" to sTick(), "crease" to sCrease(), "cinch" to sCinch(), "slip" to sSlip(), "pluck" to sPluck())
                sounds.forEach { (name, pcm) ->
                    val f = File(dir, "$name.wav")
                    if (!f.exists()) wav(f, pcm)
                    ids[name] = pool.load(f.path, 1)
                }
                ready = true
            }
        }.start()
    }

    private fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        if (!ready || !Store.sound(c)) return
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        ids[name]?.let { pool.play(it, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    private fun buzz(primitives: List<Pair<Int, Float>>, fallback: Int, gapMs: Int = 0) {
        val v = vib ?: return
        if (!Store.haptics(c) || !v.hasVibrator()) return
        runCatching {
            val prims = primitives.map { it.first }.toIntArray()
            val effect = if (v.areAllPrimitivesSupported(*prims)) {
                VibrationEffect.startComposition().apply {
                    primitives.forEachIndexed { i, (p, s) -> addPrimitive(p, s, if (i == 0) 0 else gapMs) }
                }.compose()
            } else VibrationEffect.createPredefined(fallback)
            v.vibrate(effect)
        }
    }

    fun tick(level: Float = 0.6f) {
        buzz(listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.35f + 0.5f * level), VibrationEffect.EFFECT_TICK)
        play("tick", 0.35f + 0.5f * level)
    }

    fun crease() {
        buzz(listOf(VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f), VibrationEffect.EFFECT_HEAVY_CLICK)
        play("crease")
    }

    fun cinch() {
        buzz(listOf(VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.6f, VibrationEffect.Composition.PRIMITIVE_CLICK to 1f), VibrationEffect.EFFECT_DOUBLE_CLICK, 45)
        play("cinch")
    }

    fun slip() {
        buzz(listOf(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL to 0.5f), VibrationEffect.EFFECT_TICK)
        play("slip", 0.85f)
    }

    /** [tension] is the thread's stretch: 1 at rest, higher when stretched, lower when slack. */
    fun pluck(tension: Float = 1f) {
        buzz(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.45f), VibrationEffect.EFFECT_CLICK)
        play("pluck", 0.9f, tension)
    }

    // ---- Synthesis (44.1 kHz mono), the preview's Web Audio recipes ------------------------

    private val sr = 44100

    /** A band-pass biquad whose centre can glide, for noise shaped into fibre and thread sounds. */
    private fun bandNoise(len: Int, q: Double, freq: (Double) -> Double, gain: (Double) -> Double): FloatArray {
        val out = FloatArray(len); val rnd = Random(7)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in 0 until len) {
            val t = i / sr.toDouble()
            val w = 2 * PI * freq(t) / sr; val alpha = sin(w) / (2 * q)
            val b0 = alpha; val a0 = 1 + alpha; val a1 = -2 * cos(w); val a2 = 1 - alpha
            val x = rnd.nextDouble() * 2 - 1
            val y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            out[i] = (y * gain(t)).toFloat()
        }
        return out
    }

    private fun env(t: Double, att: Double, dec: Double): Double =
        if (t < att) t / att else exp(-(t - att) / dec * ln(1000.0))

    private fun sTick() = bandNoise((sr * 0.03).toInt(), 3.0, { 3200.0 }) { 1.2 * env(it, 0.001, 0.018) }

    private fun sCrease(): FloatArray {
        val len = (sr * 0.14).toInt(); val out = FloatArray(len); var ph = 0.0
        for (i in 0 until len) {
            val t = i / sr.toDouble(); val f = 190 * Math.pow(90.0 / 190, (t / 0.08).coerceAtMost(1.0))
            ph += 2 * PI * f / sr
            out[i] = (0.55 * sin(ph) * env(t, 0.002, 0.09)).toFloat()
        }
        val click = sTick()
        for (i in click.indices) out[i] += click[i] * 0.7f
        return out
    }

    private fun sCinch(): FloatArray {
        val len = (sr * 0.3).toInt()
        val draw = bandNoise(len, 6.0, { 700 * Math.pow(2600.0 / 700, (it / 0.12).coerceAtMost(1.0)) }) { 1.4 * if (it < 0.17) env(it, 0.01, 0.12) else 0.0 }
        var ph = 0.0
        for (i in 0 until len) {
            val t = i / sr.toDouble() - 0.13
            if (t < 0) continue
            val f = 140 * Math.pow(70.0 / 140, (t / 0.07).coerceAtMost(1.0)); ph += 2 * PI * f / sr
            draw[i] += (0.6 * sin(ph) * env(t, 0.003, 0.11)).toFloat()
        }
        return draw
    }

    private fun sSlip() = bandNoise((sr * 0.28).toInt(), 4.0, { 2400 * Math.pow(320.0 / 2400, (it / 0.24).coerceAtMost(1.0)) }) { 1.3 * env(it, 0.01, 0.23) }

    /** Karplus-Strong at 165 Hz; SoundPool's rate raises or lowers it with the thread's tension. */
    private fun sPluck(): FloatArray {
        val len = (sr * 1.4).toInt(); val n = sr / 165; val line = FloatArray(n) { Random.nextFloat() * 2 - 1 }
        val out = FloatArray(len); var j = 0; var lp = 0f
        for (i in 0 until len) {
            val nx = (j + 1) % n; val v = line[j]
            lp += (v - lp) * 0.3f
            out[i] = lp * 0.5f * env(i / sr.toDouble(), 0.002, 1.3).toFloat()
            line[j] = 0.4985f * (v + line[nx]); j = nx
        }
        return out
    }

    private fun wav(f: File, pcm: FloatArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32000).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }

    companion object {
        @Volatile private var one: Feel? = null
        fun get(c: Context): Feel = one ?: synchronized(this) { one ?: Feel(c.applicationContext).also { one = it } }
    }
}
