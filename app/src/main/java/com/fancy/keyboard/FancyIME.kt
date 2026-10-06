package com.fancy.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

class FancyIME : InputMethodService(), SpellCheckerSession.SpellCheckerSessionListener {
    class Style(val name: String, val up: IntArray?, val low: IntArray?)
    class Theme(val bg: Int, val key: Int, val text: Int)

    private fun rng(base: Int) = IntArray(26) { base + it }
    private fun rng(base: Int, ov: Map<Int, Int>) = rng(base).also { a -> ov.forEach { (i, v) -> a[i] = v } }

    private val styles = listOf(
        Style("Normal", null, null),
        Style("Gothique", rng(0x1D56C), rng(0x1D586)),
        Style("Gothique clair", rng(0x1D504, mapOf(2 to 0x212D, 7 to 0x210C, 8 to 0x2111, 17 to 0x211C, 25 to 0x2128)), rng(0x1D51E)),
        Style("Double", rng(0x1D538, mapOf(2 to 0x2102, 7 to 0x210D, 13 to 0x2115, 15 to 0x2119, 16 to 0x211A, 17 to 0x211D, 25 to 0x2124)), rng(0x1D552)),
        Style("Script", rng(0x1D4D0), rng(0x1D4EA)),
        Style("Gras", rng(0x1D5D4), rng(0x1D5EE)),
        Style("Italique", rng(0x1D63C), rng(0x1D656)),
        Style("Serif gras", rng(0x1D400), rng(0x1D41A)),
        Style("Machine", rng(0x1D670), rng(0x1D68A)),
        Style("Cercles", rng(0x24B6), rng(0x24D0)),
        Style("Carrés", rng(0x1F170), rng(0x1F170))
    )

    private val themes = listOf(
        Theme(0xFF202124.toInt(), 0xFF3C4043.toInt(), 0xFFFFFFFF.toInt()),
        Theme(0xFF2A1B4D.toInt(), 0xFF4B2E83.toInt(), 0xFFFFFFFF.toInt()),
        Theme(0xFF2B0A0A.toInt(), 0xFF5C1A1A.toInt(), 0xFFFFFFFF.toInt()),
        Theme(0xFF1A1405.toInt(), 0xFF4A3A0A.toInt(), 0xFFFFE08A.toInt()),
        Theme(0xFF0B1F33.toInt(), 0xFF1D4E7A.toInt(), 0xFFFFFFFF.toInt()),
        Theme(0xFFE8EAED.toInt(), 0xFFFFFFFF.toInt(), 0xFF202124.toInt())
    )

    private val accents = listOf(
        Triple('é', 'e', 0x301), Triple('è', 'e', 0x300), Triple('à', 'a', 0x300), Triple('ù', 'u', 0x300),
        Triple('ê', 'e', 0x302), Triple('â', 'a', 0x302), Triple('î', 'i', 0x302), Triple('ô', 'o', 0x302),
        Triple('û', 'u', 0x302), Triple('ç', 'c', 0x327)
    )

    private val emojis = "😀😂🥰😍😎🤔😢😡👍🙏🔥🎉✨💯😭😅🤣😊😉🙄😴🤩😇🥳💀👀🙌💪"
        .codePoints().toArray().map { String(Character.toChars(it)) }

    private var idx = 1
    private var themeIdx = 0
    private var shift = false
    private var mode = 0 // 0 lettres, 1 symboles, 2 emojis, 3 presse-papiers
    private lateinit var root: LinearLayout
    private var bar: LinearLayout? = null

    private val units = ArrayList<Pair<String, String>>()
    private var sugg: List<String> = emptyList()
    private var fix: String? = null
    private var asked = ""
    private var session: SpellCheckerSession? = null
    private val ui = Handler(Looper.getMainLooper())

    private val clips = ArrayList<String>()
    private var cm: ClipboardManager? = null

    private val th get() = themes[themeIdx]

    override fun onCreate() {
        super.onCreate()
        val p = getSharedPreferences("fancy", Context.MODE_PRIVATE)
        idx = p.getInt("style", 1).coerceIn(0, styles.size - 1)
        themeIdx = p.getInt("theme", 0).coerceIn(0, themes.size - 1)
        try {
            val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            session = tsm.newSpellCheckerSession(null, Locale.FRENCH, this, false)
        } catch (e: Exception) { session = null }
        try {
            cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm?.addPrimaryClipChangedListener { addClip() }
        } catch (e: Exception) { cm = null }
    }

    override fun onDestroy() { session?.close(); super.onDestroy() }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        addClip()
        resetWord()
    }

    private fun save() {
        getSharedPreferences("fancy", Context.MODE_PRIVATE).edit()
            .putInt("style", idx).putInt("theme", themeIdx).apply()
    }

    private fun addClip() {
        try {
            val c = cm?.primaryClip ?: return
            if (c.itemCount == 0) return
            val t = c.getItemAt(0).coerceToText(this)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                clips.remove(t); clips.add(0, t)
                while (clips.size > 10) clips.removeAt(clips.size - 1)
            }
        } catch (e: Exception) {}
    }

    override fun onGetSuggestions(r: Array<out SuggestionsInfo>?) {}

    override fun onGetSentenceSuggestions(r: Array<out SentenceSuggestionsInfo>?) {
        val info = r?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }?.getSuggestionsInfoAt(0)
        val list = ArrayList<String>()
        var typo = false
        if (info != null) {
            for (i in 0 until info.suggestionsCount) list.add(info.getSuggestionAt(i))
            typo = (info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
        }
        ui.post { showSug(list, typo) }
    }

    private fun plain() = units.joinToString("") { it.first }
    private fun outs() = units.joinToString("") { it.second }

    private fun conv(c: Char): String {
        val st = styles[idx]
        if (st.up == null || st.low == null) return c.toString()
        return when (c) {
            in 'a'..'z' -> String(Character.toChars(st.low[c - 'a']))
            in 'A'..'Z' -> String(Character.toChars(st.up[c - 'A']))
            else -> c.toString()
        }
    }

    private fun convAccent(t: Triple<Char, Char, Int>, up: Boolean): String {
        if (idx == 0) return (if (up) t.first.uppercaseChar() else t.first).toString()
        val base = if (up) t.second.uppercaseChar() else t.second
        return conv(base) + String(Character.toChars(t.third))
    }

    private fun styled(s: String) = s.map { c ->
        val t = accents.find { it.first == c.lowercaseChar() }
        if (t != null) convAccent(t, c.isUpperCase()) else conv(c)
    }.joinToString("")

    private fun resetWord() {
        units.clear(); sugg = emptyList(); fix = null; asked = ""
        fillBar()
    }

    private fun ask() {
        val w = plain()
        fix = null; sugg = emptyList(); asked = w
        fillBar()
        if (w.length >= 2) {
            try { session?.getSentenceSuggestions(arrayOf(TextInfo(w)), 3) } catch (e: Exception) {}
        }
    }

    private fun showSug(l: List<String>, typo: Boolean) {
        if (asked != plain()) return
        sugg = l
        fix = if (typo && l.isNotEmpty() && l[0] != asked) l[0] else null
        fillBar()
    }

    private fun fillBar() {
        val b = bar ?: return
        b.removeAllViews()
        val w = plain()
        if (w.isEmpty()) return
        for (s in (listOf(w) + sugg).distinct().take(3)) b.addView(key(styled(s), 1f) { pick(s) })
    }

    private fun pick(s: String) {
        val ic = currentInputConnection ?: return
        val typed = outs()
        if (typed.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(typed.length, 0)?.toString()
            if (before == typed) ic.deleteSurroundingTextInCodePoints(typed.codePointCount(0, typed.length), 0)
            else { resetWord(); return }
        }
        ic.commitText(styled(s) + " ", 1)
        resetWord()
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1); resetWord() }

    private fun space() { val f = fix; if (f != null) pick(f) else commit(" ") }

    private fun bs() {
        val ic = currentInputConnection ?: return
        val u = units.removeLastOrNull()
        if (u != null) {
            ic.deleteSurroundingTextInCodePoints(u.second.codePointCount(0, u.second.length), 0)
            ask(); return
        }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0)
    }

    private fun typeChar(ch: Char) {
        val ic = currentInputConnection ?: return
        val c = if (shift) ch.uppercaseChar() else ch
        val out = conv(c)
        ic.commitText(out, 1)
        if (ch.isLetter()) { units.add(c.toString() to out); ask() } else resetWord()
        after()
    }

    private fun typeAccent(t: Triple<Char, Char, Int>) {
        val ic = currentInputConnection ?: return
        val out = convAccent(t, shift)
        ic.commitText(out, 1)
        units.add((if (shift) t.first.uppercaseChar() else t.first).toString() to out)
        ask()
        after()
    }

    private fun after() { if (shift) { shift = false; build() } }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        build()
        return root
    }

    private fun build() {
        root.setBackgroundColor(th.bg)
        root.removeAllViews(); bar = null
        when (mode) {
            0 -> letters()
            1 -> grid(listOf("1234567890", "@#€_&-+()/", "*\":;!?,.%="))
            2 -> emojiGrid()
            else -> clipGrid()
        }
        bottom()
    }

    private fun letters() {
        val b = newRow(); bar = b; root.addView(b); fillBar()
        val acc = newRow()
        for (t in accents) acc.addView(key(convAccent(t, shift), 1f) { typeAccent(t) })
        root.addView(acc)
        for (r in listOf("1234567890", "azertyuiop", "qsdfghjklm", "wxcvbn,.")) {
            val row = newRow()
            val last = r.startsWith("wx")
            if (last) row.addView(key("⇧", 1.4f) { shift = !shift; build() })
            for (ch in r) row.addView(key(conv(if (shift) ch.uppercaseChar() else ch), 1f) { typeChar(ch) })
            if (last) row.addView(key("⌫", 1.4f) { bs() })
            root.addView(row)
        }
    }

    private fun grid(rows: List<String>) {
        for ((i, r) in rows.withIndex()) {
            val row = newRow()
            for (ch in r) row.addView(key(ch.toString(), 1f) { commit(ch.toString()) })
            if (i == rows.size - 1) row.addView(key("⌫", 1.4f) { bs() })
            root.addView(row)
        }
    }

    private fun emojiGrid() {
        val rows = emojis.chunked(7)
        for ((i, r) in rows.withIndex()) {
            val row = newRow()
            for (e in r) row.addView(key(e, 1f) { commit(e) })
            if (i == rows.size - 1) row.addView(key("⌫", 1.4f) { bs() })
            root.addView(row)
        }
    }

    private fun clipGrid() {
        if (clips.isEmpty()) {
            val row = newRow()
            row.addView(key("Rien copié pour l'instant", 1f) { })
            root.addView(row)
        } else {
            for (c in clips.take(5)) {
                val row = newRow()
                row.addView(key(c.replace("\n", " ").take(40), 1f) { commit(c) })
                root.addView(row)
            }
        }
        val row = newRow()
        row.addView(key("🗑 Vider", 1f) { clips.clear(); build() })
        row.addView(key("⌫", 1f) { bs() })
        root.addView(row)
    }

    private fun bottom() {
        val b = newRow()
        b.addView(key(if (mode == 1) "ABC" else "?123", 1.1f) { mode = if (mode == 1) 0 else 1; build() })
        b.addView(key(if (mode == 2) "ABC" else "😀", 0.8f) { mode = if (mode == 2) 0 else 2; build() })
        b.addView(key(if (mode == 3) "ABC" else "📋", 0.8f) { mode = if (mode == 3) 0 else 3; build() })
        b.addView(key("🖌", 0.8f) { themeIdx = (themeIdx + 1) % themes.size; save(); build() })
        b.addView(key("🎨\n" + styles[idx].name, 1.9f, 10f) { idx = (idx + 1) % styles.size; save(); build() })
        b.addView(key("'", 0.6f) { commit("'") })
        b.addView(key("espace", 2.2f) { space() })
        b.addView(key("↵", 0.9f) { commit("\n") })
        root.addView(b)
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, (40 * resources.displayMetrics.density).toInt())
    }

    private fun key(label: String, weight: Float, size: Float = 14f, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = size
        setTextColor(th.text)
        setBackgroundColor(th.key)
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(2, 2, 2, 2) }
        setOnClickListener { onClick() }
    }
}
