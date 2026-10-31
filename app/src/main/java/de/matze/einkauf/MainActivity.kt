package de.matze.einkauf

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    // ---------- Farben (wie die Web-App, hell/dunkel) ----------
    private class Palette(dark: Boolean) {
        val bg = Color.parseColor(if (dark) "#101813" else "#EEF5EF")
        val surface = Color.parseColor(if (dark) "#18231C" else "#FFFFFF")
        val ink = Color.parseColor(if (dark) "#E7F0EA" else "#17201B")
        val muted = Color.parseColor(if (dark) "#93A69A" else "#5E6E64")
        val brand = Color.parseColor(if (dark) "#7FC59C" else "#1F4D3A")
        val brandInk = Color.parseColor(if (dark) "#0E1A13" else "#FFFFFF")
        val citrus = Color.parseColor("#F2C230")
        val line = Color.parseColor(if (dark) "#26362C" else "#D4E2D8")
        val danger = Color.parseColor(if (dark) "#F08A7E" else "#B3261E")
    }

    private lateinit var pal: Palette
    private lateinit var storage: Storage
    private lateinit var parser: Parser
    private lateinit var quick: List<String>
    private var items: MutableList<Item> = mutableListOf()
    private var catOrder: MutableList<String> = mutableListOf()

    // Rückgängig-Zustand
    private var lastRemoved: Pair<Item, Int>? = null
    private var lastCleared: List<Item>? = null
    private var lastAdded: List<String>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var toastHide: Runnable? = null
    private var clearDisarm: Runnable? = null

    // Views
    private lateinit var scroll: ScrollView
    private lateinit var countView: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var addBtn: Button
    private lateinit var pasteBody: LinearLayout
    private lateinit var pasteArrow: TextView
    private lateinit var quickBody: LinearLayout
    private lateinit var quickArrow: TextView
    private lateinit var chips: FlowLayout
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var clearBtn: TextView
    private lateinit var orderPanel: LinearLayout
    private lateinit var orderList: LinearLayout
    private lateinit var toast: LinearLayout
    private lateinit var toastText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        pal = Palette(dark)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        storage = Storage(this)
        parser = Parser(Storage.loadKnown(this))
        quick = Storage.loadQuick(this)
        items = storage.loadItems()
        catOrder = storage.loadOrder()

        setContentView(buildUi())
        render()

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------- Teilen aus WhatsApp & Text markieren → „Einkauf“ ----------
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        intent.action = Intent.ACTION_MAIN // nicht doppelt verarbeiten
        if (text.isNullOrBlank()) return
        val added = addText(text)
        if (added > 0) {
            showToast("$added ${if (added == 1) "Produkt" else "Produkte"} aus der Nachricht übernommen")
        } else {
            input.setText(text)
            setOpen(pasteBody, pasteArrow, true)
            status.text = "Keine neuen Produkte gefunden – Text steht oben zum Bearbeiten."
        }
    }

    // ---------- Hinzufügen ----------
    /** Zerlegt den Text, fügt neue Produkte hinzu und gibt die Anzahl zurück. */
    private fun addText(text: String): Int {
        val found = parser.localParse(text)
        fun keyOf(store: String?, t: String) = (store ?: "") + "|" + t.lowercase()
        val existing = items.map { keyOf(it.store, it.text) }.toMutableSet()
        val newIds = mutableListOf<String>()
        for (f in found) {
            val t = Parser.upperFirst(f.text)
            val k = keyOf(f.store, t)
            if (k in existing) continue
            val it = Item(Storage.newId() + newIds.size, t, f.store, parser.guessCat(t))
            items.add(it)
            existing.add(k)
            newIds.add(it.id)
        }
        if (newIds.isNotEmpty()) {
            lastAdded = newIds; lastRemoved = null; lastCleared = null
            save()
            render()
        }
        return newIds.size
    }

    private fun addFromInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { status.text = "Füg zuerst eine Nachricht ein."; return }
        val added = addText(text)
        input.setText("")
        hideKeyboard()
        status.text = if (added > 0) "$added ${if (added == 1) "Produkt" else "Produkte"} hinzugefügt." else "Keine neuen Produkte gefunden."
        if (added > 0) setOpen(pasteBody, pasteArrow, false)
    }

    private fun pasteClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (t.isNullOrBlank()) {
            status.text = "Zwischenablage ist leer."
        } else {
            input.setText(t)
            input.setSelection(t.length)
            status.text = ""
        }
    }

    // ---------- Abhaken = verschwinden ----------
    private fun tick(item: Item, row: View, box: TextView) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx < 0) return
        haptic(row)
        row.isEnabled = false
        box.text = "✓"
        (box.background as GradientDrawable).setColor(pal.citrus)
        lastRemoved = items[idx] to idx
        lastCleared = null; lastAdded = null
        items.removeAt(idx)
        save()
        row.animate().alpha(0f).translationX(row.width * 0.15f).setStartDelay(250).setDuration(300)
            .withEndAction { render() }.start()
        showToast("„${item.text}“ erledigt")
    }

    private fun undo() {
        lastAdded?.let { ids ->
            items.removeAll { it.id in ids }
            lastAdded = null
        } ?: lastCleared?.let { old ->
            items = old.toMutableList()
            lastCleared = null
        } ?: lastRemoved?.let { (item, index) ->
            items.add(minOf(index, items.size), item)
            lastRemoved = null
        } ?: return
        save()
        render()
        hideToast()
    }

    private fun clearAll() {
        if (clearDisarm != null) {
            handler.removeCallbacks(clearDisarm!!)
            clearDisarm = null
            clearBtn.text = "Ganze Liste leeren"
            clearBtn.setTextColor(pal.muted)
            lastCleared = items.toList(); lastAdded = null; lastRemoved = null
            items.clear()
            save()
            render()
            showToast("Liste geleert")
            return
        }
        clearBtn.text = "Wirklich alles löschen? Nochmal tippen"
        clearBtn.setTextColor(pal.danger)
        val r = Runnable {
            clearDisarm = null
            clearBtn.text = "Ganze Liste leeren"
            clearBtn.setTextColor(pal.muted)
        }
        clearDisarm = r
        handler.postDelayed(r, 4000)
    }

    private fun save() = storage.saveItems(items)

    // ---------- Darstellung ----------
    private fun render() {
        listBox.removeAllViews()
        // nach Laden gruppieren, Laden ohne Angabe zuletzt
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (it in items) groups.getOrPut(it.store ?: "") { mutableListOf() }.add(it)
        val keys = groups.keys.sortedBy { if (it.isEmpty()) 1 else 0 }
        val showHeads = keys.size > 1 || (keys.isNotEmpty() && keys[0].isNotEmpty())

        for (k in keys) {
            if (showHeads) listBox.addView(storeHead(if (k.isEmpty()) "Sonstiges" else k))
            val sorted = groups.getValue(k).mapIndexed { i, it -> Triple(it, i, parser.catOf(it)) }
                .sortedWith(compareBy<Triple<Item, Int, String>>({ catOrder.indexOf(it.third) }, { it.second }))
            var lastCat: String? = null
            for ((it, _, c) in sorted) {
                if (c != lastCat) {
                    listBox.addView(catHead(Parser.CAT_NAME[c] ?: c))
                    lastCat = c
                }
                listBox.addView(itemRow(it))
            }
        }

        val n = items.size
        countView.text = if (n > 0) "$n offen" else ""
        emptyView.visibility = if (n == 0) View.VISIBLE else View.GONE
        clearBtn.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n == 0) setOpen(pasteBody, pasteArrow, true)
        renderChips()
        if (orderPanel.visibility == View.VISIBLE) renderOrder()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val onList = items.map { it.text.lowercase() }.toSet()
        val free = quick.filter { it.lowercase() !in onList }
        if (free.isEmpty()) {
            chips.addView(text("Alles schon auf der Liste.", 15f, pal.muted))
            return
        }
        for (q in free) {
            val chip = text(q, 16f, pal.ink)
            chip.background = rounded(pal.bg, 999f, pal.line)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.setOnClickListener {
                haptic(it)
                val item = Item(Storage.newId(), q, null, parser.guessCat(q))
                items.add(item)
                save()
                lastRemoved = null; lastCleared = null; lastAdded = listOf(item.id)
                render()
                showToast("„$q“ hinzugefügt")
            }
            chips.addView(chip)
        }
    }

    private fun renderOrder() {
        orderList.removeAllViews()
        catOrder.forEachIndexed { i, id ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val name = text(Parser.CAT_NAME[id] ?: id, 17f, pal.ink)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(arrowButton("↑", i > 0, "${Parser.CAT_NAME[id]} nach oben") { move(i, -1) })
            row.addView(arrowButton("↓", i < catOrder.size - 1, "${Parser.CAT_NAME[id]} nach unten") { move(i, 1) })
            orderList.addView(row)
            if (i < catOrder.size - 1) orderList.addView(divider())
        }
    }

    private fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= catOrder.size) return
        val tmp = catOrder[i]; catOrder[i] = catOrder[j]; catOrder[j] = tmp
        storage.saveOrder(catOrder)
        render()
    }

    // ---------- Aufbau der Oberfläche ----------
    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(pal.bg)
        root.fitsSystemWindows = true

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(120))
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Kopf
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(2), dp(8), dp(2), dp(18))
        }
        val title = text("Einkauf", 34f, pal.ink).apply { setTypeface(typeface, Typeface.BOLD) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countView = text("", 17f, pal.muted).apply { setTypeface(typeface, Typeface.BOLD) }
        header.addView(countView)
        col.addView(header)

        // Nachricht einfügen
        val pasteCard = card()
        val pasteHead = sectionHead("Nachricht einfügen")
        pasteArrow = pasteHead.getChildAt(1) as TextView
        pasteBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        input = EditText(this).apply {
            hint = "WhatsApp-Nachricht hier einfügen – als Liste oder als normaler Text. Oder in WhatsApp „Teilen“ → Einkauf."
            setHintTextColor(pal.muted)
            setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = rounded(pal.bg, 12f, pal.line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        pasteBody.addView(input, lp(top = 4))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pasteBtn = button("Aus Zwischenablage", primary = false) { pasteClipboard() }
        addBtn = button("Zur Liste hinzufügen", primary = true) { addFromInput() }
        btnRow.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        btnRow.addView(addBtn, LinearLayout.LayoutParams(0, dp(52), 1.3f))
        pasteBody.addView(btnRow, lp(top = 10))
        status = text("", 15f, pal.muted)
        pasteBody.addView(status, lp(top = 8))
        pasteHead.setOnClickListener { toggle(pasteBody, pasteArrow) }
        pasteCard.addView(pasteHead)
        pasteCard.addView(pasteBody)
        col.addView(pasteCard, lp(bottom = 12))

        // Häufig gekauft
        val quickCard = card()
        val quickHead = sectionHead("Häufig gekauft")
        quickArrow = quickHead.getChildAt(1) as TextView
        quickBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips = FlowLayout(this, dp(8))
        quickBody.addView(chips, lp(top = 6))
        quickHead.setOnClickListener { toggle(quickBody, quickArrow) }
        quickCard.addView(quickHead)
        quickCard.addView(quickBody)
        setOpen(quickBody, quickArrow, false)
        col.addView(quickCard, lp(bottom = 8))

        // Liste
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(48), dp(16), 0)
        }
        emptyView.addView(text("Nichts mehr zu holen.", 22f, pal.ink).apply {
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        })
        emptyView.addView(text("Füg die nächste Nachricht oben ein oder teile sie aus WhatsApp.", 17f, pal.muted).apply {
            gravity = Gravity.CENTER
        }, lp(top = 6))
        col.addView(emptyView)

        // Fußzeile
        val orderBtn = linkButton("Laufweg ändern") {
            if (orderPanel.visibility == View.VISIBLE) {
                orderPanel.visibility = View.GONE
            } else {
                orderPanel.visibility = View.VISIBLE
                renderOrder()
                scroll.post { scroll.smoothScrollTo(0, orderPanel.top) }
            }
        }
        col.addView(orderBtn, lp(top = 18))
        clearBtn = linkButton("Ganze Liste leeren") { clearAll() }
        col.addView(clearBtn, lp(top = 4))

        // Laufweg-Panel
        orderPanel = card().apply { visibility = View.GONE }
        orderPanel.addView(text("Laufweg im Supermarkt", 20f, pal.ink).apply { setTypeface(typeface, Typeface.BOLD) })
        orderPanel.addView(text("Bring die Bereiche in die Reihenfolge, in der du durch den Laden gehst.", 15f, pal.muted), lp(top = 4, bottom = 8))
        orderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        orderPanel.addView(orderList)
        orderPanel.addView(button("Fertig", primary = true) {
            orderPanel.visibility = View.GONE
            scroll.smoothScrollTo(0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        col.addView(orderPanel, lp(top = 16))

        // Toast mit Rückgängig
        toast = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.ink, 14f, null)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            visibility = View.GONE
            elevation = dp(6).toFloat()
        }
        toastText = text("", 16f, pal.bg)
        toast.addView(toastText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val undoBtn = text("Rückgängig", 16f, Color.parseColor("#17201B")).apply {
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(pal.citrus, 10f, null)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { undo() }
        }
        toast.addView(undoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(20))
        })

        return root
    }

    private fun itemRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.surface, 14f, pal.line)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(56)
            isClickable = true
            contentDescription = "${item.text} abhaken"
        }
        val box = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#17201B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), pal.brand)
            }
        }
        row.addView(box, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
        row.addView(text(item.text, 19f, pal.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { tick(item, row, box) }
        row.layoutParams = lp(bottom = 8)
        return row
    }

    private fun storeHead(name: String) = text(name, 22f, pal.brand).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(14), dp(2), dp(2))
    }

    private fun catHead(name: String) = text(name.uppercase(), 13f, pal.muted).apply {
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.surface, 18f, pal.line)
        setPadding(dp(16), dp(12), dp(16), dp(14))
    }

    private fun sectionHead(title: String): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            isClickable = true
        }
        head.addView(text(title, 18f, pal.ink).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("▾", 18f, pal.muted))
        return head
    }

    private fun toggle(body: View, arrow: TextView) = setOpen(body, arrow, body.visibility != View.VISIBLE)

    private fun setOpen(body: View, arrow: TextView, open: Boolean) {
        body.visibility = if (open) View.VISIBLE else View.GONE
        arrow.text = if (open) "▾" else "▸"
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) pal.brandInk else pal.ink)
        background = rounded(if (primary) pal.brand else pal.bg, 12f, if (primary) null else pal.line)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun linkButton(label: String, onClick: () -> Unit) = text(label, 16f, pal.muted).apply {
        gravity = Gravity.CENTER
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun arrowButton(label: String, enabled: Boolean, desc: String, onClick: () -> Unit) = text(label, 18f, pal.ink).apply {
        gravity = Gravity.CENTER
        background = rounded(pal.bg, 10f, pal.line)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginStart = dp(8) }
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        contentDescription = desc
        if (enabled) setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(pal.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun haptic(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun showToast(msg: String) {
        toastText.text = msg
        toast.visibility = View.VISIBLE
        toast.alpha = 0f
        toast.translationY = dp(40).toFloat()
        toast.animate().alpha(1f).translationY(0f).setDuration(200).start()
        toastHide?.let { handler.removeCallbacks(it) }
        val r = Runnable { hideToast() }
        toastHide = r
        handler.postDelayed(r, 4000)
    }

    private fun hideToast() {
        toastHide?.let { handler.removeCallbacks(it) }
        toastHide = null
        toast.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(200)
            .withEndAction { toast.visibility = View.GONE }.start()
    }
}

/** Einfaches Umbruch-Layout für die Schnellauswahl-Chips. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
