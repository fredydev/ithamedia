package com.ithamedia.pdfsigner

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import java.text.Normalizer
import java.util.Locale

/** Rubriques du catalogue, numérotées comme les chapitres d'un cahier. */
enum class Category(val number: String, val title: String) {
    ORGANIZE("01", "Organiser"),
    CREATE("02", "Créer & convertir"),
    SIGN("03", "Remplir & signer"),
    EDIT("04", "Annoter & modifier"),
    PROTECT("05", "Protéger"),
    INSPECT("06", "Optimiser & inspecter"),
}

/**
 * Catalogue complet des outils. [onPdf] : l'outil s'applique à un PDF existant (proposé dans
 * la visionneuse et le menu des fichiers).
 */
enum class Tool(
    val category: Category,
    val title: String,
    val subtitle: String,
    @DrawableRes val icon: Int,
    val keywords: String = "",
    val onPdf: Boolean = true,
) {
    ORGANIZE(Category.ORGANIZE, "Organiser les pages", "Réordonner, pivoter, dupliquer, supprimer", R.drawable.ic_reorder, "trier ordre déplacer rotation"),
    MERGE(Category.ORGANIZE, "Combiner des fichiers", "Plusieurs PDF en un seul", R.drawable.ic_merge, "fusionner assembler joindre merge"),
    SPLIT(Category.ORGANIZE, "Fractionner", "Découper en plusieurs PDF", R.drawable.ic_scissors, "diviser couper séparer split"),
    EXTRACT(Category.ORGANIZE, "Extraire des pages", "Copier une sélection dans un nouveau PDF", R.drawable.ic_extract, "sélection copier"),
    INSERT(Category.ORGANIZE, "Insérer des pages", "Pages blanches ou tirées d'un autre PDF", R.drawable.ic_insert, "ajouter page blanche"),
    CROP(Category.ORGANIZE, "Rogner", "Recadrer les marges des pages", R.drawable.ic_crop, "marges recadrer découper"),

    SCAN(Category.CREATE, "Scanner un document", "Bords détectés, perspective corrigée", R.drawable.ic_scan, "caméra photo numériser", onPdf = false),
    IMAGES(Category.CREATE, "Images en PDF", "Assembler des photos de la galerie", R.drawable.ic_image, "photo jpg png galerie", onPdf = false),
    WORD(Category.CREATE, "Word en PDF", "Convertir un document .docx", R.drawable.ic_word, "docx office convertir", onPdf = false),
    TEXT(Category.CREATE, "Texte en PDF", "Saisir ou coller un texte", R.drawable.ic_type_file, "txt note écrire", onPdf = false),
    BLANK(Category.CREATE, "PDF vierge", "Pages blanches A4, A5 ou Lettre", R.drawable.ic_blank, "nouveau vide", onPdf = false),
    OCR(Category.CREATE, "Reconnaître le texte (OCR)", "Rendre un scan cherchable et copiable", R.drawable.ic_ocr, "ocr reconnaissance texte scan"),
    TO_IMAGES(Category.CREATE, "PDF en images", "PNG ou JPEG, page par page", R.drawable.ic_export_image, "exporter png jpg"),
    TO_TEXT(Category.CREATE, "PDF en texte", "Récupérer le texte brut (.txt)", R.drawable.ic_text, "exporter txt copier"),
    TO_WORD(Category.CREATE, "PDF en Word", "Texte récupéré dans un .docx modifiable", R.drawable.ic_word, "exporter docx office"),
    EXTRACT_IMAGES(Category.CREATE, "Extraire les images", "Toutes les images du document", R.drawable.ic_files, "photos récupérer"),

    SIGN(Category.SIGN, "Remplir et signer", "Signature, initiales, date, coches", R.drawable.ic_signature, "signature parapher initiales"),
    FORM(Category.SIGN, "Remplir un formulaire", "Champs interactifs du PDF", R.drawable.ic_form, "champs cases cocher formulaire"),
    STAMP(Category.SIGN, "Tamponner", "Approuvé, Payé, Confidentiel…", R.drawable.ic_stamp, "tampon cachet"),

    ANNOTATE(Category.EDIT, "Annoter", "Stylo, surligneur, formes, notes", R.drawable.ic_pen, "dessiner commenter surligner flèche"),
    ADD_IMAGE(Category.EDIT, "Ajouter une image", "Logo, photo ou cachet sur une page", R.drawable.ic_layers, "logo insérer image"),
    WATERMARK(Category.EDIT, "Filigrane", "Texte en transparence sur les pages", R.drawable.ic_watermark, "brouillon confidentiel"),
    HEADER_FOOTER(Category.EDIT, "En-tête et pied de page", "Texte répété en haut ou en bas", R.drawable.ic_heading, "entête pied"),
    PAGE_NUMBERS(Category.EDIT, "Numéroter les pages", "Page 1 sur 12, 1/12…", R.drawable.ic_page_number, "pagination numéro"),
    BATES(Category.EDIT, "Numérotation Bates", "Référence unique sur chaque page", R.drawable.ic_hash, "juridique cote référence"),

    PROTECT(Category.PROTECT, "Protéger par mot de passe", "Chiffrement AES 256 bits", R.drawable.ic_lock, "chiffrer sécuriser verrouiller"),
    UNLOCK(Category.PROTECT, "Retirer le mot de passe", "Déverrouiller un PDF protégé", R.drawable.ic_unlock, "déchiffrer ouvrir"),
    REDACT(Category.PROTECT, "Caviarder", "Masquer définitivement texte et zones", R.drawable.ic_redact, "noircir anonymiser rgpd"),
    SANITIZE(Category.PROTECT, "Supprimer les infos cachées", "Métadonnées, scripts, pièces jointes", R.drawable.ic_sanitize, "nettoyer confidentialité"),

    COMPRESS(Category.INSPECT, "Compresser", "Alléger sans perte visible", R.drawable.ic_compress, "réduire taille poids optimiser"),
    COMPARE(Category.INSPECT, "Comparer deux PDF", "Repérer les différences page par page", R.drawable.ic_compare, "différences versions"),
    PROPERTIES(Category.INSPECT, "Propriétés", "Titre, auteur, polices, sécurité", R.drawable.ic_info, "métadonnées infos");

    fun matches(query: String): Boolean {
        val q = fold(query)
        if (q.isEmpty()) return true
        val hay = fold("$title $subtitle $keywords ${category.title}")
        return q.split(' ').filter { it.isNotEmpty() }.all { hay.contains(it) }
    }

    fun intent(context: Context): Intent {
        fun of(cls: Class<*>) = Intent(context, cls).putExtra(EXTRA_TOOL, name)
        return when (this) {
            ORGANIZE, EXTRACT, INSERT -> of(OrganizeActivity::class.java)
            MERGE -> of(MergeActivity::class.java)
            SPLIT -> of(SplitActivity::class.java)
            CROP -> of(CropActivity::class.java)
            SCAN -> of(ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_SCAN)
            IMAGES -> of(ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_GALLERY)
            WORD -> of(WordToPdfActivity::class.java)
            TEXT -> of(TextToPdfActivity::class.java)
            BLANK -> of(BlankPdfActivity::class.java)
            OCR -> of(OcrActivity::class.java)
            TO_IMAGES, TO_TEXT, TO_WORD, EXTRACT_IMAGES -> of(ExportActivity::class.java)
            SIGN, STAMP, ADD_IMAGE -> of(SignActivity::class.java)
            FORM -> of(FormActivity::class.java)
            ANNOTATE -> of(AnnotateActivity::class.java)
            WATERMARK, HEADER_FOOTER, PAGE_NUMBERS, BATES -> of(StampTextActivity::class.java)
            PROTECT -> of(ProtectActivity::class.java)
            UNLOCK -> of(UnlockActivity::class.java)
            REDACT -> of(RedactActivity::class.java)
            SANITIZE -> of(SanitizeActivity::class.java)
            COMPRESS -> of(CompressActivity::class.java)
            COMPARE -> of(CompareActivity::class.java)
            PROPERTIES -> of(PropertiesActivity::class.java)
        }
    }

    companion object {
        const val EXTRA_TOOL = "tool"

        fun from(intent: Intent, default: Tool): Tool =
            intent.getStringExtra(EXTRA_TOOL)?.let { runCatching { valueOf(it) }.getOrNull() } ?: default

        fun fold(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()
    }
}
