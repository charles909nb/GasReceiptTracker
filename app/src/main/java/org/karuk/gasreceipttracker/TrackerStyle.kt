package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Native controls and shapes only; no large artwork or remote dependencies. */
object TrackerStyle {
    val red = Color.rgb(164, 36, 43)
    val ink = Color.rgb(30, 39, 52)
    val muted = Color.rgb(93, 105, 120)
    val background = Color.rgb(245, 246, 248)
    val line = Color.rgb(217, 222, 229)
    val blush = Color.rgb(252, 238, 238)
    val green = Color.rgb(34, 107, 76)
    fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()
    fun shape(c: Context, color: Int, border: Int? = null, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
        border?.let { setStroke(dp(c, 1), it) }
    }
    fun text(c: Context, value: String, size: Float = 16f, bold: Boolean = false, color: Int = ink) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(c, 2).toFloat(), 1f)
    }
    fun button(c: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        text = title; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else red)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(25, 0, 0, 0)),
            shape(c, if (primary) red else Color.WHITE, if (primary) null else line), null)
        minHeight = dp(c, 50); minimumHeight = dp(c, 50)
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(c, 5), 0, dp(c, 5)) }
        setOnClickListener { action() }
    }
    fun input(c: Context, e: EditText) {
        e.setTextColor(ink); e.setHintTextColor(muted); e.textSize = 16f
        e.background = shape(c, Color.WHITE, line, 10)
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12)); e.minimumHeight = dp(c, 52)
        e.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12) }
        e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    fun card(c: Context, title: String, detail: String, emphasis: Boolean = false): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c, if (emphasis) blush else Color.WHITE, if (emphasis) null else line)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(c, 6), 0, dp(c, 10)) }
        addView(text(c, title, 17f, true, if (emphasis) red else ink))
        if (detail.isNotBlank()) addView(text(c, detail, 14f, false, muted).apply { setPadding(0, dp(c, 6), 0, 0) })
    }
    fun insets(a: Activity, root: View) {
        a.window.statusBarColor = red
        a.window.navigationBarColor = Color.WHITE
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val i = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(i.left, i.top, i.right, i.bottom); windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
