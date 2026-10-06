package com.fancy.keyboard

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class FancyIME : InputMethodService() {
    class Style(val name: String, val up: IntArray?, val low: IntArray?)

    private fun rng(base: Int) = IntArray(26) { base + it }
    private fun rng(base: Int, fix: Map<Int, Int>) = rng(base).also { a -> fix.forEach { (i, v) -> a[i] = v } }

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

    private val accents = listOf(
        Triple('é', 'e', 0x301), Triple('è', 'e', 0x300), Triple('à', 'a', 0x300), Triple('ù', 'u', 0x300),
        Triple('ê', 'e', 0x302), Triple('â', 'a', 0x302), Triple('î', 'i', 0x302), Triple('ô', 'o', 0x302),
        Triple('û', 'u', 0x302), Triple('ç', 'c', 0x327)
    )
    private var idx = 1
    private var shift = false
    private lateinit var root: LinearLayout

    private fun conv(c: Char): String {
        val st = styles[idx]
        if (st.up == null || st.low == null) return c.toString()
        return when (c) {
            in 'a'..'z' -> String(Character.toChars(st.low[c - 'a']))
            in 'A'..'Z' -> String(Character.toChars(st.up[c - 'A']))
            else -> c.toString()
        }
    }

    private fun convAccent(t: Triple<Char, Char, Int>): String {
        val base = if (shift) t.second.uppercaseChar() else t.second
        return if (idx == 0) (if (shift) t.first.uppercaseChar() else t.first).toString()
        else conv(base) + String(Character.toChars(t.third))
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202124.toInt())
            setPadding(4, 8, 4, 8)
        }
        build()
        return root
    }

    private fun after() { if (shift) { shift = false; build() } }

    private fun build() {
        root.removeAllViews()
        val acc = newRow()
        for (t in accents) acc.addView(key(convAccent(t), 1f) {
            currentInputConnection?.commitText(convAccent(t), 1); after()
        })
        root.addView(acc)
        val rows = listOf("1234567890", "azertyuiop", "qsdfghjklm", "wxcvbn,.'")
        for (r in rows) {
            val row = newRow()
            for (ch in r) {
                val out = if (shift) ch.uppercaseChar() else ch
                row.addView(key(conv(out), 1f) {
                    currentInputConnection?.commitText(conv(out), 1); after()
                })
            }
            if (r.startsWith("wx")) row.addView(key("⌫", 1.5f) {
                val ic = currentInputConnection
                if (ic != null) {
                    val sel = ic.getSelectedText(0)
                    if (!sel.isNullOrEmpty()) ic.commitText("", 1)
                    else ic.deleteSurroundingTextInCodePoints(1, 0)
                }
            })
            root.addView(row)
        }
        val bottom = newRow()
        bottom.addView(key("⇧", 1f) { shift = !shift; build() })
        bottom.addView(key("🎨 " + styles[idx].name, 2.5f) { idx = (idx + 1) % styles.size; build() })
        bottom.addView(key("espace", 4f) { currentInputConnection?.commitText(" ", 1) })
        bottom.addView(key("↵", 1.5f) { currentInputConnection?.commitText("\n", 1) })
        root.addView(bottom)
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, (44 * resources.displayMetrics.density).toInt())
    }

    private fun key(label: String, weight: Float, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF3C4043.toInt())
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(2, 2, 2, 2) }
        setOnClickListener { onClick() }
    }
}
