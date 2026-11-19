package com.cerniera.nodo

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Segmented
import com.cerniera.kit.Sheet
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.pressable
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import com.cerniera.kit.withAlpha
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Nodo: reminders as knots on one thread, the crease as now. The thread fills the glass; the line
 * for a new knot, a knot's detail and the settings come forward only when asked for.
 */
class MainActivity : Activity(), ThreadView.Listener {
    private lateinit var feel: Feel
    private lateinit var root: FrameLayout
    private lateinit var thread: ThreadView
    private lateinit var scrim: View
    private lateinit var panel: FrameLayout
    private lateinit var permit: TextView
    private var knots: MutableList<Knot> = mutableListOf()
    private var open: Int? = null
    /** The open knot's text field, so an edit in progress survives the panel being rebuilt. */
    private var whatField: EditText? = null
    private var tieField: EditText? = null
    private var tieKnot: Knot? = null
    private var insets = intArrayOf(0, 0, 0, 0)

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Nodo")) return
        // Opened from the widget: it learns where it sits from the tap
        NodoWidget.seatFrom(this, intent)
        // Visconti, always: the thread is drawn for the dark
        Kit.dark = true
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0; window.navigationBarColor = 0
        feel = Feel.get(this)

        when (intent?.getStringExtra("seed")) {
            "examples" -> Store.save(this, Store.examples())
            // Mechack's own case on the Fold: one knot on the thread
            "one" -> Store.save(this, mutableListOf(Knot(1, "Run bath", System.currentTimeMillis() + 50 * MIN)))
        }
        knots = Store.load(this)

        root = FrameLayout(this).apply { setBackgroundColor(Kit.Fixed.VISCONTI_GROUND) }
        thread = ThreadView(this, feel, this)
        root.addView(thread, FrameLayout.LayoutParams(-1, -1))
        scrim = View(this).apply { setBackgroundColor(0x59050807); alpha = 0f; visibility = View.GONE; setOnClickListener { closeDetail() } }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel = FrameLayout(this).apply { visibility = View.GONE; elevation = px(6).toFloat() }
        root.addView(panel, FrameLayout.LayoutParams(-2, -2))
        permit = text("", 13f, Kit.Fixed.IVORY, 500).apply {
            setPadding(px(14), px(9), px(16), px(9))
            background = rounded(0xFF1C2522.toInt(), px(18).toFloat(), withAlpha(Kit.Fixed.ROSSO, 0.6f), px(1))
            visibility = View.GONE; pressable()
            setOnClickListener { askPermits() }
        }
        root.addView(permit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnApplyWindowInsetsListener { _, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insets = intArrayOf(b.left, b.top, b.right, b.bottom)
            (permit.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + px(14)
            i
        }
        setContentView(root)
        thread.knots = knots
        record()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); NodoWidget.seatFrom(this, i); if (::thread.isInitialized) handle(i) }

    /** From a notification (a knot's id), or the emulator's walk-through (open, add). */
    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("add")?.let { s ->
            val q = cued(s); val p = Words.parse(q.first, System.currentTimeMillis())
            knots += Knot(Store.newId(knots), p.text, p.due ?: System.currentTimeMillis() + HOUR, cue = if (p.due == null) q.second else null); changed()
        }
        // The emulator test sends a moment as the family's apps will
        i.getStringExtra("cue")?.let { what -> Cues.fire(this, what); knots.clear(); knots += Store.load(this); thread.kick() }
        val id = i.getIntExtra("knot", -1)
        root.post {
            when {
                id >= 0 -> knots.firstOrNull { it.id == id }?.let { tapped(it) }
                i.getStringExtra("open") == "settings" -> settings()
                i.getStringExtra("open") == "widget" -> widgetSheet()
                i.getStringExtra("open") == "scene" -> scene()
                // Test only: ask the launcher to place the widget, as "Add to home screen" does
                i.getStringExtra("open") == "pin" -> getSystemService(android.appwidget.AppWidgetManager::class.java)
                    ?.requestPinAppWidget(android.content.ComponentName(this, NodoWidget::class.java), null, null)
                i.getStringExtra("open") == "next" -> knots.filter { it.done == null }.minByOrNull { it.due }?.let { tapped(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After a crash Nodo opens on the report alone (Crash.show): nothing else exists yet
        if (!::thread.isInitialized) return
        // Done or Later may have been pressed on a notification while Nodo was away
        if (tieField == null) { knots = Store.load(this); thread.knots = knots; open?.let { id -> knots.firstOrNull { it.id == id }?.let { showDetail(it) } ?: closeDetail() } }
        Reminders.arm(this, knots)
        permits()
        // The backup for home: opened on home's Wi-Fi, knots tied away from home come due
        Thread {
            if (Cues.checkHere(applicationContext) > 0) runOnUiThread {
                if (tieField == null) { knots = Store.load(this); thread.knots = knots; thread.invalidate() }
            }
        }.start()
        root.post { if (thread.lowerHalfFree() && open == null) showNext() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        // After a crash Nodo opens on the report alone (Crash.show): nothing else exists yet
        if (!::thread.isInitialized) return
        // Folding or turning: the thread lays itself out again, and the detail follows it
        root.post {
            when {
                // Tying a knot: the field follows its knot to the thread's new place, text and all
                tieField != null -> { val f = tieField; val k = tieKnot; if (f != null && k != null) f.layoutParams = tiePlace(k) }
                thread.lowerHalfFree() -> (open?.let { id -> knots.firstOrNull { it.id == id } } ?: next())?.let { showDetail(it) }
                open != null && panelTransient -> knots.firstOrNull { it.id == open }?.let { showDetail(it) } ?: closeDetail()
                else -> closeDetail()
            }
        }
    }

    @Deprecated("Back closes what came forward")
    override fun onBackPressed() {
        when {
            !::thread.isInitialized -> @Suppress("DEPRECATION") super.onBackPressed()
            tieField != null -> endTie()
            open != null && panelTransient -> closeDetail()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---- The thread's listener -------------------------------------------------------------

    override fun changed() { Store.save(this, knots); thread.kick(); open?.let { id -> knots.firstOrNull { it.id == id }?.let { refreshDetail(it) } } }

    override fun tapped(k: Knot) { showDetail(k) }

    override fun tying() = tieField != null

    override fun tie(k: Knot) {
        tieKnot = k
        val f = EditText(this).apply {
            hint = "Friday 9 call the bank"
            textSize = 15f
            setTextColor(Kit.Fixed.IVORY); setHintTextColor(0xFF6E716A.toInt())
            typeface = Kit.sans(context, 400)
            background = rounded(0xFF1C2522.toInt(), px(14).toFloat(), Kit.Fixed.CHAMPAGNE, px(1))
            setPadding(px(12), px(9), px(12), px(9))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "What to remember"
            setOnEditorActionListener { _, action, ev -> if (action == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { endTie(); true } else false }
        }
        tieField = f
        root.addView(f, tiePlace(k))
        f.requestFocus()
        f.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(f, 0) }, 60)
    }

    /** Where the tie field sits: beside its knot, wherever the thread now runs. */
    private fun tiePlace(k: Knot): FrameLayout.LayoutParams {
        val p = thread.at(k)
        val fw = px(240)
        val lp = FrameLayout.LayoutParams(fw, -2)
        if (thread.orientationHorizontal()) { lp.leftMargin = (p.x - fw / 2).toInt().coerceIn(px(12), root.width - fw - px(12)); lp.topMargin = (p.y + px(34)).toInt() }
        else { lp.leftMargin = (p.x + px(22)).toInt().coerceAtMost(root.width - fw - px(12)); lp.topMargin = (p.y - px(18)).toInt() }
        return lp
    }

    override fun endTie() {
        val f = tieField ?: return; val k = tieKnot
        tieField = null; tieKnot = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(f.windowToken, 0)
        root.removeView(f)
        if (k == null) return
        val q = cued(f.text.toString())
        val p = Words.parse(q.first, System.currentTimeMillis())
        if (p.text.isEmpty()) { knots.remove(k); feel.slip() }
        else {
            k.text = p.text; p.due?.let { k.due = it; feel.tick() }; k.pending = false
            // A moment in the words ("when I'm home"): the knot waits for it, unless a time was given too
            if (q.second != null && p.due == null) { k.cue = q.second; feel.tick() }
            Store.setSeenHint(this)
        }
        changed()
    }

    // ---- A knot's detail ------------------------------------------------------------------

    /** Transient everywhere but the main screen turned, where the lower half always holds a knot. */
    private val panelTransient get() = !thread.lowerHalfFree()

    private fun next() = knots.filter { it.done == null }.minByOrNull { it.due }

    private fun showNext() { next()?.let { showDetail(it) } }

    private fun showDetail(k: Knot) {
        if (tieField != null) endTie()
        // Turning or folding rebuilds the panel: keep what was being typed into this knot
        val carry = whatField?.takeIf { open == k.id && it.hasFocus() }?.let { it.text.toString() to it.selectionEnd }
        open = k.id; thread.selected = k.id
        panel.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF19211E.toInt(), px(26).toFloat(), withAlpha(Kit.Fixed.CHAMPAGNE, 0.18f), px(1))
            setPadding(px(18), px(16), px(18), px(14))
            isClickable = true
        }
        fill(body, k)
        carry?.let { (t, at) -> whatField?.apply { setText(t); requestFocus(); setSelection(at.coerceIn(0, t.length)) } }
        val maxH = (root.height * (if (thread.shape == ThreadView.Shape.COVER_P) 0.72f else 1f)).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxOf(px(120), maxH - px(32) - insets[1] - insets[3]), MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        place(k)
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE; panel.alpha = 0f; panel.translationY = px(14).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(Kit.GLIDE).setInterpolator(Kit.ease).start()
        }
        if (panelTransient) { scrim.visibility = View.VISIBLE; scrim.animate().alpha(1f).setDuration(Kit.SETTLE).start() }
        else { scrim.alpha = 0f; scrim.visibility = View.GONE }
    }

    private fun refreshDetail(k: Knot) { if (open == k.id && panel.visibility == View.VISIBLE) showDetail(k) }

    /** Where the detail goes: the half away from the knot, the lower half, or the foot of the cover. */
    private fun place(k: Knot) {
        val lp = FrameLayout.LayoutParams(-1, -2)
        val (l, t, r, b) = insets.toList()
        val w = root.width; val h = root.height
        when (thread.shape) {
            ThreadView.Shape.MAIN_L -> { lp.gravity = Gravity.TOP; lp.leftMargin = l + px(28); lp.rightMargin = r + px(28); lp.topMargin = h / 2 + px(20); lp.height = h / 2 - px(20) - px(20) - b }
            ThreadView.Shape.MAIN_P -> {
                val onRight = thread.at(k).x > w / 2
                lp.width = w / 2 - px(48); lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.leftMargin = if (onRight) l + px(24) else w / 2 + px(24); lp.bottomMargin = b + px(24)
            }
            ThreadView.Shape.COVER_L -> { lp.width = minOf(px(460), (w * 0.52f).toInt()); lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL; lp.rightMargin = r + px(16); lp.topMargin = t + px(12); lp.bottomMargin = b + px(12) }
            ThreadView.Shape.COVER_P -> { lp.gravity = Gravity.BOTTOM; lp.leftMargin = l + px(12); lp.rightMargin = r + px(12); lp.bottomMargin = b + px(14) }
        }
        panel.layoutParams = lp
    }

    private fun closeDetail() {
        if (thread.lowerHalfFree()) { showNext(); return }
        open = null; thread.selected = null
        panel.animate().alpha(0f).translationY(px(10).toFloat()).setDuration(Kit.QUICK).withEndAction { panel.visibility = View.GONE; panel.removeAllViews() }.start()
        scrim.animate().alpha(0f).setDuration(Kit.QUICK).withEndAction { scrim.visibility = View.GONE }.start()
    }

    private fun label(s: String) = text(s.uppercase(), 10.5f, 0xFF6E716A.toInt(), 500).apply { letterSpacing = 0.2f; setPadding(0, px(14), 0, px(7)) }

    private fun fill(body: LinearLayout, k: Knot) {
        val now = System.currentTimeMillis()
        val what = EditText(this).apply {
            whatField = this
            setText(k.text); textSize = 20f; setTextColor(Kit.Fixed.IVORY); typeface = Kit.sans(context, 400)
            background = null; setPadding(0, px(2), 0, px(6)); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "Reminder"
            fun commit() {
                val q = cued(text.toString())
                val p = Words.parse(q.first, System.currentTimeMillis())
                if (p.text.isNotEmpty() && (p.text != k.text || p.due != null || q.second != null)) {
                    k.text = p.text; p.due?.let { k.due = it; k.done = null; k.cue = null }
                    if (q.second != null && p.due == null) { k.cue = q.second; k.done = null }
                    changed()
                }
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0); clearFocus()
            }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { commit(); true } else false }
        }
        body.addView(what, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(this).apply { setBackgroundColor(withAlpha(Kit.Fixed.CHAMPAGNE, 0.18f)) }, LinearLayout.LayoutParams(-1, px(1)))
        body.addView(text(Words.whenLine(k, now), 13.5f, if (k.late(now)) 0xFFFF6A6F.toInt() else Kit.Fixed.CHAMPAGNE, 400).apply { setPadding(0, px(8), 0, 0) })

        body.addView(label("Later"))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        later(now).forEachIndexed { i, (name, t) ->
            chips.addView(text(name, 13.5f, Kit.Fixed.IVORY, 400).apply {
                setPadding(px(13), px(8), px(13), px(8))
                background = rounded(0xFF101613.toInt(), px(18).toFloat(), withAlpha(Kit.Fixed.CHAMPAGNE, 0.18f), px(1))
                pressable(); setOnClickListener { feel.tick(); k.due = snap(t, System.currentTimeMillis()); k.done = null; k.cue = null; changed() }
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = px(6) })
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) })

        body.addView(label("Weight"))
        body.addView(Segmented(this, listOf("Light", "Firm", "Tight"), k.weight - 1) { i ->
            k.weight = i + 1
            for (j in 0..i) body.postDelayed({ feel.tick(0.5f + j * 0.25f) }, j * 60L)
            Store.save(this, knots); thread.kick()
        })
        body.addView(label("Repeat"))
        body.addView(Segmented(this, listOf("Once", "Daily", "Mon–Fri", "Weekly"), Knot.REPEATS.indexOf(k.repeat).coerceAtLeast(0)) { i ->
            k.repeat = Knot.REPEATS[i]; Store.save(this, knots); thread.kick()
        })

        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(16), 0, 0) }
        acts.addView(primary(if (k.done != null) "Not done" else "Done") {
            if (k.done != null) { k.done = null; feel.slip(); if (k.due < System.currentTimeMillis()) k.due = snap(System.currentTimeMillis() + HOUR, System.currentTimeMillis()) }
            else { Store.done(knots, k); feel.cinch() }
            Store.save(this, knots); thread.kick()
            if (panelTransient) closeDetail() else showNext()
        }.apply { setPadding(px(22), px(11), px(22), px(11)); textSize = 14.5f })
        acts.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun ghost(s: String, go: () -> Unit) = text(s, 14f, 0xFFA9A496.toInt(), 400).apply { setPadding(px(10), px(10), px(10), px(10)); pressable(); setOnClickListener { go() } }
        acts.addView(ghost("Untie") {
            knots.remove(k); feel.slip(); Store.save(this, knots); thread.kick()
            if (panelTransient) closeDetail() else { open = null; showNext(); if (next() == null) { panel.visibility = View.GONE } }
        })
        if (panelTransient) acts.addView(ghost("Close") { closeDetail() })
        body.addView(acts)
    }

    private fun later(now: Long): List<Pair<String, Long>> {
        val z = ZoneId.systemDefault(); val today = LocalDate.now(z)
        fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(z).toInstant().toEpochMilli()
        val out = mutableListOf("+15 min" to now + 15 * MIN, "+1 h" to now + HOUR)
        if (at(today, 19) > now + HOUR) out += "Tonight" to at(today, 19)
        out += "Tomorrow 09:00" to at(today.plusDays(1), 9)
        out += "Monday 09:00" to at(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 9)
        return out
    }

    // ---- Fluido: knots that wait for a moment ----------------------------------------------

    private var settingsSheet: Sheet? = null
    private val ASK_HOME = 41
    private val ASK_ALWAYS = 42
    /** Location given back after it was turned off for Nodo: then "Allow all the time" too. */
    private val ASK_FINE = 43

    /**
     * The words without their moment, and the moment as a cue: home, or an installed app found by
     * its name. An app Nodo can't find leaves the words as they were, so nothing is lost.
     */
    private fun cued(raw: String): Pair<String, String?> {
        val c = Words.cue(raw)
        if (c.home) return c.text to Knot.HOME
        val name = c.app ?: return raw to null
        val app = Cues.findApp(this, name) ?: return raw to null
        return c.text to Knot.app(app.first, app.second)
    }

    // ---- Settings: a tap on the name ------------------------------------------------------

    override fun settings() {
        if (tieField != null) endTie()
        settingsSheet = Sheet.show(this, "Nodo") { s ->
            val ctx = this
            s.body.addView(label("Feel"))
            s.body.addView(Card(ctx).apply {
                row(Row(ctx, "Sound", "Ticks, the crease, a knot pulled tight, the thread plucked. Quiet when the phone is on silent.",
                    Toggle(ctx, Store.sound(ctx)) { Store.setSound(ctx, it); if (it) feel.tick() }))
                row(Row(ctx, "Haptics", "The same moments, felt.", Toggle(ctx, Store.haptics(ctx)) { Store.setHaptics(ctx, it); if (it) feel.cinch() }))
            })
            s.body.addView(label("Reminders"))
            val exact = Reminders.canExact(ctx)
            val notes = notificationsOn()
            s.body.addView(Card(ctx).apply {
                row(Row(ctx, "On time", if (exact) "Knots arrive at their minute." else "Without Alarms & reminders, Android may deliver a knot a few minutes late.",
                    endValue(if (exact) "Allowed" else "Allow", if (exact) Kit.MUTED else Kit.CHAMPAGNE)) {
                    if (!exact) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                })
                row(Row(ctx, "Notifications", if (notes) "A knot reaching now says so, with Done and Later." else "Off: knots will come due silently.",
                    endValue(if (notes) "On" else "Turn on", if (notes) Kit.MUTED else Kit.CHAMPAGNE)) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                })
            })
            s.body.addView(label("Moments"))
            s.body.addView(Card(ctx).apply {
                val home = Cues.homeNetwork(ctx)
                val waitingHome = knots.count { it.waiting && it.cue == Knot.HOME }
                val homeNote = if (home == null) "Type \"when I'm home, water the plants\" and the knot waits for home. Set home while you're on its Wi-Fi. Android asks for location, then for \"Allow all the time\", so Nodo can read the network's name while it's closed."
                    else "Home is the Wi-Fi \"$home\"." + (if (waitingHome > 0) " $waitingHome waiting." else "") + Cues.lastWifi(ctx).let { if (it.isEmpty()) "" else " Last Wi-Fi: $it." }
                row(Row(ctx, "Home", homeNote, endValue(if (home == null) "I'm home now" else "Forget", if (home == null) Kit.CHAMPAGNE else Kit.MUTED)) {
                    if (home != null) { Cues.forgetHome(ctx); settingsSheet?.dismiss(); settings() }
                    else if (!Cues.canReadWifi(ctx)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), ASK_HOME)
                    else setHomeNow()
                })
                // What stops home being noticed, said plainly, with the one tap that fixes it
                Cues.problem(ctx)?.let { p ->
                    val (title, note, action) = when (p) {
                        Cues.Problem.NO_PERMISSION -> Triple("Location is off for Nodo", "Nodo can't read the Wi-Fi's name, so it can't tell you're home.", "Allow")
                        Cues.Problem.NOT_ALWAYS -> Triple("Only while Nodo is open", "Choose \"Allow all the time\" so Nodo notices home while it's closed. It only reads the Wi-Fi's name when Wi-Fi connects; nothing is tracked.", "Allow")
                        Cues.Problem.LOCATION_OFF -> Triple("Location is off", "Android hides Wi-Fi names while location is off, so home can't be noticed.", "Turn on")
                        Cues.Problem.WIFI_OFF -> Triple("Wi-Fi is off", "Home is noticed when the phone joins its Wi-Fi.", "Turn on")
                    }
                    row(Row(ctx, title, note, endValue(action, Kit.CHAMPAGNE)) { fixHome(p) })
                }
                val apps = knots.filter { it.waiting }.mapNotNull { k -> k.cue?.takeIf { it.startsWith("app:") }?.substringAfter('|') }.distinct()
                row(Row(ctx, "Apps", if (apps.isEmpty()) "Type \"next time I open Spotify, add the album\" and the knot waits for that app. Fold Taskbar tells Nodo when it opens."
                    else "Waiting for " + apps.joinToString(", ") + ". Fold Taskbar tells Nodo when one opens.", null))
            })
            s.body.addView(label("On the home screen"))
            widgetCard(s.body)
            s.body.addView(label("How it works"))
            listOf(
                "Pull the loose end" to "A knot forms under your finger. Type what to remember; a time in the words (\"Friday 9\", \"in 20 min\", \"tomorrow at 3pm\") moves it there.",
                "Drag a knot" to "Along the thread to move it; across the crease to mark it done.",
                "Stretch the thread" to "Spread today out or squeeze next week in. It eases back on its own.",
                "Tap a knot" to "Later, weight, repeat, done or untie.",
            ).forEach { (t, d) ->
                s.body.addView(text(t, 15f, Kit.INK, 500), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
                s.body.addView(text(d, 13.5f, Kit.MUTED, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(3) })
            }
            s.body.addView(doctrineCard(listOf(
                "Membrane" to "One thread hung through every knot. Drag one and the thread follows the finger while its neighbours slide to make room.",
                "Concentrato" to "Moving fast, the day marks and times drop first, then the words, leaving knots sized by weight.",
                "Giulietta" to "No add button: the loose end ties a knot, the crease marks it done, and the detail comes forward only when a knot is tapped.",
            )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26) })
        }
    }

    // ---- The widget ------------------------------------------------------------------------

    /**
     * The widget: a live picture of it over a banded ground, then its look. With Fold Widgets
     * installed it follows Fold Widgets' look by default (finish, corners, glass images); otherwise,
     * or with following off, Nodo's own choices of the same: eleven finishes, four corners, an image for
     * each screen's glass. Then the wallpaper permission and Add to home screen.
     */
    private fun widgetCard(into: LinearLayout) {
        val ctx = this
        val d = resources.displayMetrics.density
        val pic = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY }
        val bands = Finishes.stripes()
        fun draw() {
            val look = Look.read(ctx)
            val w = 300f; val h = 150f
            val out = android.graphics.Bitmap.createBitmap((w * d).toInt(), (h * d).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(out)
            cv.drawBitmap(bands, null, android.graphics.RectF(0f, 0f, w * d, h * d), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val inner = Painter.draw(ctx, w - 24f, h - 24f, d, look.finish, com.cerniera.kit.Ground.of(bands, 0, true), knots, System.currentTimeMillis(), look.radius.toFloat()).bitmap
            cv.drawBitmap(inner, null, android.graphics.RectF(12f * d, 12f * d, (w - 12f) * d, (h - 12f) * d), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            pic.setImageBitmap(out)
        }
        draw()
        into.addView(text("The thread, as a widget: overdue knots against now, and the next one with Done. It never shows calendar events; the Agenda widget keeps those.", 13.5f, Kit.MUTED, 400),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
        into.addView(pic, LinearLayout.LayoutParams(px(300), px(150)).apply { bottomMargin = px(16) })

        val lookBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            lookBox.removeAllViews()
            if (Look.foldWidgets(ctx)) {
                val follows = Look.follows(ctx)
                val now = Look.read(ctx)
                val corner = Look.CORNERS.firstOrNull { it.second == now.radius }?.first ?: "${now.radius} dp"
                lookBox.addView(Card(ctx).apply {
                    row(Row(ctx, "Follow Fold Widgets",
                        if (follows) "Its finish, corners and glass images dress this widget too: ${now.finish.short}, $corner corners. Change them in Fold Widgets."
                        else "This widget keeps its own look, chosen below.",
                        Toggle(ctx, follows) { on -> Look.setFollows(ctx, on); feel.tick(); fill(); draw() }))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
                if (follows) return
            }
            lookBox.addView(label("Finish"))
            lookBox.addView(Finishes.grid(ctx, Store.widgetFinish(ctx)) { f -> feel.tick(); Store.setWidgetFinish(ctx, f); draw() })
            lookBox.addView(label("Corners"))
            lookBox.addView(Segmented(ctx, Look.CORNERS.map { it.first }, Look.CORNERS.indexOfFirst { it.second == Look.radius(ctx) }.coerceAtLeast(0)) { i ->
                Look.setRadius(ctx, Look.CORNERS[i].second); draw()
            })
            lookBox.addView(label("Glass"))
            lookBox.addView(Card(ctx).apply {
                for ((screen, name) in listOf("cover" to "Cover screen image", "main" to "Main screen image")) {
                    val set = Look.ownImage(ctx, screen) != null
                    row(Row(ctx, name, if (set) "Your own image is used behind the glass on this screen"
                        else "Uses the system wallpaper. On a live wallpaper, or if the glass shows the wrong picture, choose the image you see on this screen.",
                        endValue(if (set) "Clear" else "Choose", Kit.CHAMPAGNE)) {
                        if (set) { Look.setOwnImage(ctx, screen, null); fill(); draw() }
                        else { pendingImage = screen; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 20) }
                    })
                }
            })
        }
        fill()
        refillLook = { fill(); draw() }
        into.addView(lookBox)

        val seeing = com.cerniera.kit.Backdrop.canReadWallpaper()
        into.addView(Card(ctx).apply {
            row(Row(ctx, "See the wallpaper", if (seeing) "The finishes read the wallpaper behind the widget: where Fold Widgets' live mode sees it, or from your first tap." else
                "Vetro and Cristallo show the wallpaper itself only with all-files access, as in Fold Widgets. Without it they take its colours.",
                endValue(if (seeing) "Allowed" else "Allow", if (seeing) Kit.MUTED else Kit.CHAMPAGNE)) {
                if (!seeing) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            })
            val wm = getSystemService(android.appwidget.AppWidgetManager::class.java)
            if (wm?.isRequestPinAppWidgetSupported == true) row(Row(ctx, "Add to home screen", "Your launcher asks where to put it.", endValue("Add", Kit.CHAMPAGNE)) {
                wm.requestPinAppWidget(android.content.ComponentName(ctx, NodoWidget::class.java), null, null)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
    }

    private var pendingImage: String? = null
    private var refillLook: (() -> Unit)? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data; val screen = pendingImage
        if (requestCode == 20 && resultCode == RESULT_OK && uri != null && screen != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Look.setOwnImage(this, screen, uri.toString())
            refillLook?.invoke()
        }
        pendingImage = null
    }

    /**
     * The widget at one size, drawn at its true size in dp and shown whole (for the emulator's
     * photographs): --es size 820x780. Without a size, the four everyday sizes at once.
     */
    private fun widgetSheet() {
        val d = resources.displayMetrics.density
        val now = System.currentTimeMillis()
        intent?.getStringExtra("radius")?.toIntOrNull()?.let { Look.setRadius(this, it) }
        intent?.getStringExtra("finish")?.let { n -> runCatching { com.cerniera.kit.Finish.valueOf(n) }.getOrNull()?.let { Store.setWidgetFinish(this, it) } }
        // Over the banded ground the finish tiles use, so the glass shows what it does to detail
        val bands = Finishes.stripes()
        val bandGround = com.cerniera.kit.Ground.of(bands, 0, true)
        val wanted = intent?.getStringExtra("size")?.split("x")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 }
        val sizes = if (wanted != null) listOf(wanted[0] to wanted[1]) else listOf(150f to 150f, 160f to 340f, 370f to 170f, 520f to 220f)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), insets[1] + px(12), px(12), px(12)); gravity = Gravity.CENTER }
        sizes.forEach { (w, h) ->
            col.addView(text("${w.toInt()} × ${h.toInt()} dp", 12f, Kit.MUTED, 500))
            col.addView(android.widget.ImageView(this).apply {
                setImageBitmap(Painter.draw(this@MainActivity, w, h, d, Store.widgetFinish(this@MainActivity), bandGround, knots, now, Look.radius(this@MainActivity).toFloat()).bitmap)
                background = android.graphics.drawable.BitmapDrawable(resources, bands)
                adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(-1, if (wanted != null) -1 else px(h.toInt())).apply { topMargin = px(6); bottomMargin = px(12) })
        }
        root.addView(FrameLayout(this).apply { setBackgroundColor(0xFF3A4A44.toInt()); addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(col) }); isClickable = true }, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * Test only: a home screen made up for comparing finishes. The wallpaper at [wall] fills the
     * screen as a launcher would, and the widget sits on it in each of [finishes], one below the
     * other, each reading the slice of wallpaper truly behind it: --es wall /sdcard/Download/x.jpg
     * --es finishes GOCCIA,CRISTALLO,VETRO
     */
    private fun scene() {
        val d = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
        val src = android.graphics.BitmapFactory.decodeFile(intent?.getStringExtra("wall") ?: return) ?: return
        val wall = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
        val k = maxOf(sw / src.width.toFloat(), sh / src.height.toFloat())
        android.graphics.Canvas(wall).apply { translate((sw - src.width * k) / 2f, (sh - src.height * k) / 2f); scale(k, k); drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
        val fins = (intent?.getStringExtra("finishes") ?: "GOCCIA,CRISTALLO,VETRO").split(",").mapNotNull { runCatching { com.cerniera.kit.Finish.valueOf(it.trim()) }.getOrNull() }
        val now = System.currentTimeMillis()
        val wDp = minOf(380f, sw / d - 32f); val hDp = 180f
        val gap = ((sh / d - 80f - fins.size * hDp) / (fins.size + 1)).coerceAtLeast(12f)
        val out = wall.copy(android.graphics.Bitmap.Config.ARGB_8888, true); val cv = android.graphics.Canvas(out)
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(this@MainActivity, 600); textSize = 12f * d; color = 0xFFFFFFFF.toInt(); setShadowLayer(4f * d, 0f, 0f, 0xCC000000.toInt()); letterSpacing = 0.12f }
        fins.forEachIndexed { i, f ->
            val left = (sw - wDp * d) / 2f; val top = (40f + gap + i * (hDp + gap)) * d
            val m = 0.15f * minOf(wDp, hDp) * d
            // The slice behind the widget, with what lies just beyond each edge, as Backdrop cuts it
            val sx = (left - m).toInt(); val sy = (top - m).toInt()
            val cw = (wDp * d + 2 * m).toInt(); val ch = (hDp * d + 2 * m).toInt()
            val slice = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(slice).drawBitmap(wall, -sx.toFloat(), -sy.toFloat(), null)
            val s = com.cerniera.kit.Backdrop.INNER_WIDTH / (wDp * d)
            val small = android.graphics.Bitmap.createScaledBitmap(slice, (cw * s).toInt(), (ch * s).toInt(), true)
            val ground = com.cerniera.kit.Ground.of(small, (m * s).toInt(), true)
            val pic = Painter.draw(this, wDp, hDp, d, f, ground, knots, now, Look.radius(this).toFloat()).bitmap
            cv.drawBitmap(pic, null, android.graphics.RectF(left, top, left + wDp * d, top + hDp * d), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            cv.drawText(f.short.uppercase(), left + 4f * d, top - 8f * d, label)
        }
        root.addView(android.widget.ImageView(this).apply { setImageBitmap(out); scaleType = android.widget.ImageView.ScaleType.FIT_XY; isClickable = true }, FrameLayout.LayoutParams(-1, -1))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun notificationsOn() = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /** Only what's needed, only when it's missing: a small pill at the foot. */
    private fun permits() {
        val exact = Reminders.canExact(this); val notes = notificationsOn()
        permit.text = when { !notes -> "Notifications are off · Turn on"; !exact -> "Reminders may be late · Allow"; else -> "" }
        permit.visibility = if (permit.text.isEmpty() || knots.none { it.done == null }) View.GONE else View.VISIBLE
    }

    private fun askPermits() {
        if (!notificationsOn()) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        else if (!Reminders.canExact(this)) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); permits()
        if (requestCode == ASK_HOME && Cues.canReadWifi(this)) setHomeNow()
        if (requestCode == ASK_FINE) { if (Cues.canReadWifi(this) && !Cues.canReadInBackground(this)) askAlways() else { settingsSheet?.dismiss(); settings() } }
        if (requestCode == ASK_ALWAYS) {
            // Refused (or asked too often): Android no longer offers it here, so open Nodo's own page
            if (!Cues.canReadInBackground(this) && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) appSettings()
            settingsSheet?.dismiss(); settings()
        }
    }

    private fun setHomeNow() {
        val name = Cues.setHome(this)
        android.widget.Toast.makeText(this, if (name != null) "Home is \"$name\"" else "Couldn't read this Wi-Fi's name. Check Wi-Fi and location are on.", android.widget.Toast.LENGTH_LONG).show()
        // Step two: "Allow all the time", which Android shows on its own page
        if (name != null && !Cues.canReadInBackground(this)) askAlways()
        else { settingsSheet?.dismiss(); settings() }
    }

    private fun askAlways() = requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), ASK_ALWAYS)

    private fun appSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun fixHome(p: Cues.Problem) = when (p) {
        Cues.Problem.NO_PERMISSION -> requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), ASK_FINE)
        Cues.Problem.NOT_ALWAYS -> askAlways()
        Cues.Problem.LOCATION_OFF -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        Cues.Problem.WIFI_OFF -> startActivity(Intent(Settings.Panel.ACTION_WIFI))
    }

    // ---- For the shapes test: where the parts that must be in view ended up -------------------

    private fun record() {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (thread.height <= 0) return
                root.postDelayed({
                    val o = IntArray(2).also { thread.getLocationInWindow(it) }
                    val boxes = thread.parts().entries.joinToString(", ") { (k, r) -> "\"$k\": [${(r.left + o[0]).toInt()}, ${(r.top + o[1]).toInt()}, ${(r.right + o[0]).toInt()}, ${(r.bottom + o[1]).toInt()}]" }
                    val json = "{\"shape\": \"${thread.shape}\", \"rotation\": ${display?.rotation ?: 0}, \"origin\": [${o[0]}, ${o[1]}], \"view\": [${thread.width}, ${thread.height}], " +
                        "\"insets\": [${insets[0]}, ${insets[1]}, ${insets[2]}, ${insets[3]}], \"parts\": {$boxes}}"
                    runCatching { java.io.File(filesDir, "layout.json").writeText(json) }
                }, 400)
            }
        })
    }
}
