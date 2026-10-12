package com.ithamedia.pdfsigner

import android.content.Context
import android.content.Intent
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Les outils de l'app, tels qu'affichés sur l'écran d'accueil. */
enum class Tool(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @DrawableRes val icon: Int,
    @ColorRes val color: Int,
) {
    SCAN(R.string.tool_scan, R.string.tool_scan_sub, R.drawable.ic_scan, R.color.tool_scan),
    IMAGES(R.string.tool_images, R.string.tool_images_sub, R.drawable.ic_image, R.color.tool_images),
    SIGN(R.string.tool_sign, R.string.tool_sign_sub, R.drawable.ic_signature, R.color.tool_sign),
    REORDER(R.string.tool_reorder, R.string.tool_reorder_sub, R.drawable.ic_reorder, R.color.tool_reorder),
    MERGE(R.string.tool_merge, R.string.tool_merge_sub, R.drawable.ic_merge, R.color.tool_merge),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_sub, R.drawable.ic_compress, R.color.tool_compress),
    WORD(R.string.tool_word, R.string.tool_word_sub, R.drawable.ic_word, R.color.tool_word);

    fun intent(context: Context): Intent = when (this) {
        SCAN -> Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_SCAN)
        IMAGES -> Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_GALLERY)
        SIGN -> Intent(context, SignActivity::class.java)
        REORDER -> Intent(context, ReorderActivity::class.java)
        MERGE -> Intent(context, MergeActivity::class.java)
        COMPRESS -> Intent(context, CompressActivity::class.java)
        WORD -> Intent(context, WordToPdfActivity::class.java)
    }
}
