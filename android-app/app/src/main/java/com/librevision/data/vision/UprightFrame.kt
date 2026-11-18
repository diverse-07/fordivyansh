package com.librevision.data.vision

import android.graphics.*

/** The original depth crop/rotation, applied to the detector's shared camera bitmap. */
object UprightFrame {
    /** May return [raw] unchanged. Caller must transfer ownership or recycle each bitmap once. */
    fun fromBitmap(raw: Bitmap, crop: Rect, rotation: Int): Bitmap =
        Bitmap.createBitmap(
            raw,
            crop.left,
            crop.top,
            crop.width(),
            crop.height(),
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
}
