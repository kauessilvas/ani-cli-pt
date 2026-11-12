package dev.aniclipt.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Shared text roles from STYLE_GUIDE.md; feature screens may override sizes for available space. */
object AniCliType {
    val SectionHeading = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val RowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val CardTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val Control = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val Metadata = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

// Keep Material 3's default styles until each feature adopts the semantic text roles above.
internal val AniCliTypography = Typography()
