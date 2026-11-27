package io.github.caffelatte23.futaba.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.caffelatte23.futaba.R

enum class LabelPalette(
    val stored: String,
    @StringRes val title: Int,
) {
    Default("", R.string.label_color_default),
    Yellow("#FFC83D", R.string.label_color_yellow),
    Green("#58A47C", R.string.label_color_green),
    Blue("#5B92D6", R.string.label_color_blue),
    Pink("#D8749C", R.string.label_color_pink),
    Purple("#A98ACD", R.string.label_color_purple),
    ;

    val swatch: Color?
        get() = stored.takeIf(String::isNotEmpty)?.let { Color(0xFF000000 or it.drop(1).toLong(16)) }
}
