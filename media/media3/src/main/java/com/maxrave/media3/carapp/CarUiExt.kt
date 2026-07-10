package com.maxrave.media3.carapp

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarIconSpan
import androidx.core.graphics.drawable.IconCompat
import com.maxrave.media3.R

/**
 * Secondary-text line with a static equalizer glyph in front of [suffix],
 * marking the currently playing row. Static by design: animating it requires
 * re-sending the whole template every frame (~420KB of binder traffic per
 * tick), which floods the host and starves its input handling.
 */
internal fun CarContext.nowPlayingText(suffix: String): CharSequence =
    SpannableString(if (suffix.isBlank()) " " else "  $suffix").apply {
        setSpan(
            CarIconSpan.create(
                CarIcon
                    .Builder(IconCompat.createWithResource(this@nowPlayingText, R.drawable.ic_car_now_playing))
                    .build(),
                CarIconSpan.ALIGN_CENTER,
            ),
            0,
            1,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }
