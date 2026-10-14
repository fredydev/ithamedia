package com.ithamedia.pdfsigner

import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

/** Remplir les champs interactifs (AcroForm) d'un PDF : texte, cases, boutons radio, listes. */
class FormActivity : FormToolActivity() {
    override val defaultTool = Tool.FORM
    override val primaryLabel get() = getString(R.string.form_action)

    private enum class Kind { TEXT, CHECK, CHOICE }

    private class FieldSpec(
        val name: String,
        val label: String,
        val kind: Kind,
        val value: String,
        val options: List<String> = emptyList(),
        val exports: List<String> = emptyList(),
        val multiline: Boolean = false,
    )

    private var specs: List<FieldSpec>? = null
    private val inputs = mutableListOf<Pair<FieldSpec, Any>>()
    private var flatten: MaterialSwitch? = null

    override fun onSourceReady(file: File) {
        runTask(getString(R.string.loading), { readFields(file) }) {
            specs = it
            rebuild()
        }
    }

    private fun readFields(file: File): List<FieldSpec> = PdfOps.open(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return@use emptyList()
        form.fieldTree.filterIsInstance<PDTerminalField>().mapNotNull { f ->
            val label = (f.alternateFieldName ?: f.partialName ?: f.fullyQualifiedName ?: "Champ").trim()
            val name = f.fullyQualifiedName ?: return@mapNotNull null
            runCatching {
                when (f) {
                    is PDTextField -> FieldSpec(name, label, Kind.TEXT, f.value.orEmpty(), multiline = f.isMultiline)
                    is PDCheckBox -> FieldSpec(name, label, Kind.CHECK, if (f.isChecked) "1" else "")
                    is PDRadioButton -> {
                        val values = f.exportValues.takeIf { it.isNotEmpty() } ?: f.onValues.toList()
                        FieldSpec(name, label, Kind.CHOICE, f.valueAsString, values, values)
                    }
                    is PDChoice -> {
                        val display = f.optionsDisplayValues
                        val exports = f.optionsExportValues.takeIf { it.size == display.size } ?: display
                        FieldSpec(name, label, Kind.CHOICE, f.value.firstOrNull().orEmpty(), display, exports)
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }

    override fun buildOptions(o: Options) {
        val list = specs ?: return
        inputs.clear()
        if (list.isEmpty()) {
            o.section(getString(R.string.form_none_title))
            o.note(getString(R.string.form_none_body))
            o.add(MaterialButton(this).apply {
                text = getString(R.string.tool_sign_instead)
                setOnClickListener { source?.let { FileActions.launchTool(this@FormActivity, Tool.SIGN, listOf(it)) } }
            }, 16)
            return
        }
        o.section(resources.getQuantityString(R.plurals.fields_count, list.size, list.size))
        for (spec in list) {
            when (spec.kind) {
                Kind.TEXT -> inputs += spec to o.text(spec.label, spec.value, multiline = spec.multiline)
                Kind.CHECK -> inputs += spec to o.switch(spec.label, checked = spec.value.isNotEmpty())
                Kind.CHOICE -> {
                    o.section(spec.label)
                    val selected = spec.exports.indexOf(spec.value).coerceAtLeast(0)
                    inputs += spec to o.choice(spec.options.ifEmpty { listOf("—") }, selected)
                }
            }
        }
        o.section(getString(R.string.after_filling))
        flatten = o.switch(getString(R.string.flatten_form), getString(R.string.flatten_form_sub))
    }

    private fun rebuild() {
        val host = findViewById<android.widget.LinearLayout>(R.id.options)
        host.removeAllViews()
        options = Options(host) {}
        buildOptions(options)
    }

    override fun job(source: File?): (() -> ToolOutput)? {
        val file = source ?: return null
        if (specs.isNullOrEmpty()) {
            toast(R.string.form_none_title)
            return null
        }
        val values = inputs.map { (spec, view) ->
            spec to when (view) {
                is TextInputEditText -> view.text.toString()
                is MaterialSwitch -> if (view.isChecked) "1" else ""
                is Options.Choice -> spec.exports.getOrNull(view.index).orEmpty()
                else -> ""
            }
        }
        val flat = flatten?.isChecked == true
        return {
            ToolOutput.Single(produce(output("${file.baseName()}_rempli.pdf")) { out ->
                PdfOps.open(file).use { doc ->
                    val form = doc.documentCatalog.acroForm ?: throw UserMessageException(getString(R.string.form_none_title))
                    for ((spec, value) in values) {
                        val field = form.getField(spec.name) ?: continue
                        try {
                            when (field) {
                                is PDCheckBox -> if (value.isNotEmpty()) field.check() else field.unCheck()
                                is PDTextField -> field.value = value
                                is PDRadioButton -> if (value.isNotEmpty()) field.value = value
                                is PDChoice -> if (value.isNotEmpty()) field.setValue(value)
                            }
                        } catch (_: Exception) {
                            // Apparence impossible à générer (police manquante) : la visionneuse la recréera
                            form.needAppearances = true
                            if (field is PDTextField) field.cosObject.setString(COSName.V, value)
                        }
                    }
                    if (flat) form.flatten()
                    doc.save(out)
                }
            })
        }
    }
}
