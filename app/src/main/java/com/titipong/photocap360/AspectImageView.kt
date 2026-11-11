package com.titipong.photocap360

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView that is always twice as wide as it is tall (equirectangular thumbnails). */
class AspectImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2)
    }
}
