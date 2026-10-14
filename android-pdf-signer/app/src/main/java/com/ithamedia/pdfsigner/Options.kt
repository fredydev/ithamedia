package com.ithamedia.pdfsigner

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Construit les réglages d'un outil, dans le style de l'atelier. [onChange] rafraîchit l'aperçu. */
class Options(private val container: LinearLayout, private val onChange: () -> Unit) {
    private val ctx: Context = container.context
    private val dp = ctx.resources.displayMetrics.density

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = (top * dp).toInt() }

    fun section(title: String): TextView = TextView(ctx).apply {
        setTextAppearance(R.style.TextAppearance_Atelier_Label)
        text = title
        container.addView(this, lp(28))
    }

    fun note(text: String): TextView = TextView(ctx).apply {
        setTextAppearance(R.style.TextAppearance_Atelier_Body)
        this.text = text
        container.addView(this, lp(10))
    }

    fun text(
        hint: String,
        initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        multiline: Boolean = false,
        password: Boolean = false,
    ): TextInputEditText {
        val layout = TextInputLayout(ctx)
        layout.hint = hint
        if (password) layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val edit = TextInputEditText(layout.context)
        edit.inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> inputType
        }
        // Android affiche les mots de passe en chasse fixe par défaut : on garde la police du thème
        if (password) edit.typeface = android.graphics.Typeface.DEFAULT
        if (multiline) {
            edit.minLines = 6
            edit.gravity = Gravity.TOP or Gravity.START
        } else {
            edit.isSingleLine = true
        }
        edit.setText(initial)
        edit.doAfterTextChanged { onChange() }
        layout.addView(edit)
        container.addView(layout, lp(10))
        return edit
    }

    fun switch(title: String, subtitle: String? = null, checked: Boolean = false): MaterialSwitch {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_RowTitle)
            text = title
        })
        if (subtitle != null) texts.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_Body)
            text = subtitle
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, _ -> onChange() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        container.addView(row, lp(4))
        container.addView(View(ctx).apply { setBackgroundResource(R.drawable.divider_hairline) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        return sw
    }

    class Choice(val group: ChipGroup, private val ids: List<Int>) {
        val index: Int get() = ids.indexOf(group.checkedChipId).coerceAtLeast(0)
    }

    fun choice(items: List<String>, selected: Int = 0): Choice {
        val group = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = items.mapIndexed { i, label ->
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = i == selected
            }
            group.addView(chip)
            chip.id
        }
        group.setOnCheckedStateChangeListener { _, _ -> onChange() }
        container.addView(group, lp(10))
        return Choice(group, ids)
    }

    fun slider(title: String, from: Float, to: Float, step: Float, value: Float, format: (Float) -> String): Slider {
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_RowTitle)
            text = title
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_Number)
            text = format(value)
        }
        header.addView(valueView)
        container.addView(header, lp(14))
        val slider = Slider(ctx).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = value.coerceIn(from, to)
            setLabelFormatter { format(it) }
            addOnChangeListener { _, v, _ ->
                valueView.text = format(v)
                onChange()
            }
        }
        container.addView(slider, lp(0))
        return slider
    }

    fun add(view: View, topDp: Int = 10) = container.addView(view, lp(topDp))

    /** Ligne « libellé — valeur » pour les fiches d'information. */
    fun info(label: String, value: String) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        row.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_Meta)
            text = label
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f))
        row.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Atelier_RowTitle)
            textSize = 14f
            text = value
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f))
        container.addView(row, lp(0))
        container.addView(View(ctx).apply { setBackgroundResource(R.drawable.divider_hairline) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
    }

    class Cards(private val cards: List<com.google.android.material.card.MaterialCardView>) {
        val index: Int get() = cards.indexOfFirst { it.isChecked }.coerceAtLeast(0)
    }

    /** Choix exclusif présenté en cartes (titre + explication). */
    fun cards(items: List<Pair<String, String>>, selected: Int = 0): Cards {
        val views = mutableListOf<com.google.android.material.card.MaterialCardView>()
        items.forEachIndexed { i, (title, subtitle) ->
            val card = com.google.android.material.card.MaterialCardView(ctx, null, 0).apply {
                setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.surface))
                strokeWidth = dp.toInt().coerceAtLeast(1)
                setStrokeColor(androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.selector_card_stroke))
                radius = 14 * dp
                cardElevation = 0f
                isCheckable = true
                checkedIconTint = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(ctx, R.color.accent))
                isChecked = i == selected
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (44 * dp).toInt(), (14 * dp).toInt())
            }
            inner.addView(TextView(ctx).apply {
                setTextAppearance(R.style.TextAppearance_Atelier_RowTitle)
                text = title
            })
            inner.addView(TextView(ctx).apply {
                setTextAppearance(R.style.TextAppearance_Atelier_Body)
                text = subtitle
            })
            card.addView(inner)
            card.setOnClickListener {
                views.forEach { it.isChecked = it === card }
                onChange()
            }
            views += card
            container.addView(card, lp(10))
        }
        return Cards(views)
    }
}
