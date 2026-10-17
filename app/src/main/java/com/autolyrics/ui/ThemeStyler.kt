package com.autolyrics.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.autolyrics.R
import com.google.android.material.button.MaterialButton

/**
 * Applies a [Palette] to a whole view tree, so layouts keep plain XML.
 *
 * `android:tag` picks a look: "glass" (frosted card), "pill" (rounded glass bar),
 * "accent", "sub", "dim", "hint" (text roles), "chip" (unselected chip button),
 * "chip-on" (selected). Untagged text gets a role from its original XML color
 * (bright → text, grey → sub, purple → accent), remembered for later restyles.
 */
object ThemeStyler {

    /** Puts the aurora background behind [layoutRes], styles it, colors the system bars. */
    fun install(activity: AppCompatActivity, layoutRes: Int): View {
        val p = Themes.current(activity)
        val frame = FrameLayout(activity)
        frame.addView(AuroraView(activity).also { it.setPalette(p) }, ViewGroup.LayoutParams(-1, -1))
        val content = activity.layoutInflater.inflate(layoutRes, frame, false)
        frame.addView(content)
        activity.setContentView(frame)
        style(content, p)

        activity.window.statusBarColor = p.bgTop
        activity.window.navigationBarColor = p.bgBottom
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !p.dark
            isAppearanceLightNavigationBars = !p.dark
        }
        return content
    }

    fun glass(view: View, p: Palette, radiusDp: Float): GradientDrawable {
        val d = view.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(p.card)
            setStroke((1 * d).toInt().coerceAtLeast(1), p.border)
        }
    }

    fun styleChip(button: MaterialButton, p: Palette, selected: Boolean) {
        val d = button.resources.displayMetrics.density
        button.backgroundTintList = ColorStateList.valueOf(if (selected) p.chip else p.card)
        button.strokeColor = ColorStateList.valueOf(if (selected) p.chipBorder else p.border)
        button.strokeWidth = (1 * d).toInt().coerceAtLeast(1)
        button.cornerRadius = (18 * d).toInt()
        button.setTextColor(p.text)
        button.iconTint = ColorStateList.valueOf(p.text)
    }

    fun style(view: View, p: Palette) {
        val tag = view.tag as? String
        when {
            tag == "glass" -> view.background = glass(view, p, 22f)
            tag == "pill" -> view.background = glass(view, p, 24f)
            // Old solid panel/bar colors give way to the aurora background.
            view is ViewGroup && view.background is ColorDrawable -> view.background = null
            view !is TextView && view !is ViewGroup && view.background is ColorDrawable ->
                view.setBackgroundColor(p.border) // dividers
        }

        when (view) {
            is SwitchCompat -> {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                view.thumbTintList = ColorStateList(states, intArrayOf(p.accent, if (p.dark) Color.LTGRAY else Color.WHITE))
                view.trackTintList = ColorStateList(states, intArrayOf(p.chipBorder, p.border))
                view.setTextColor(p.text)
            }
            is CompoundButton -> {
                view.buttonTintList = ColorStateList.valueOf(p.accent)
                view.setTextColor(p.text)
            }
            is MaterialButton -> styleChip(view, p, tag == "chip-on")
            is Button -> {
                view.backgroundTintList = ColorStateList.valueOf(p.card)
                view.setTextColor(p.text)
            }
            is EditText -> {
                view.setTextColor(p.text)
                view.setHintTextColor(p.dim)
                view.backgroundTintList = ColorStateList.valueOf(p.chipBorder)
            }
            is TextView -> view.setTextColor(colorFor(view, tag, p))
            is ImageButton -> view.imageTintList = ColorStateList.valueOf(p.sub)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) style(view.getChildAt(i), p)
        }
    }

    private fun colorFor(view: TextView, tag: String?, p: Palette): Int {
        val role = when (tag) {
            "accent", "sub", "dim", "hint" -> tag
            else -> (view.getTag(R.id.theme_role) as? String) ?: roleFromXmlColor(view.currentTextColor)
                .also { view.setTag(R.id.theme_role, it) }
        }
        return when (role) {
            "accent" -> p.accent
            "sub" -> p.sub
            "dim" -> p.dim
            "hint" -> p.hint
            else -> p.text
        }
    }

    private fun roleFromXmlColor(c: Int): String {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        if (b > 200 && r > 150 && g < 160) return "accent" // the old purple
        val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        return if (luma < 0.7) "sub" else "text"
    }
}
