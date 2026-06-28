package com.blueridge.parkwaynav.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/** Rasterizes a vector drawable into a [BitmapDescriptor] for use as a map marker icon. */
object MapBitmaps {
    fun fromVector(context: Context, resId: Int, sizeDp: Int = 48): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(context, resId)
            ?: return BitmapDescriptorFactory.defaultMarker()
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
