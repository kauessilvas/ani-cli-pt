package dev.aniclipt.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun AniCliTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (light) LightAniCliColors else DarkAniCliColors,
        typography = AniCliTypography,
        content = content,
    )
}

@Preview(name = "Tema escuro", showBackground = true)
@Composable
private fun DarkThemePreview() {
    ThemePreview(light = false)
}

@Preview(name = "Tema claro", showBackground = true)
@Composable
private fun LightThemePreview() {
    ThemePreview(light = true)
}

@Composable
private fun ThemePreview(light: Boolean) {
    AniCliTheme(light) {
        Column(Modifier.background(Canvas).padding(24.dp)) {
            Text("Anime em destaque", color = Ink, style = AniCliType.SectionHeading)
            Text("Ver episódios", color = Accent, style = AniCliType.Control)
            Text("Metadados verificados", color = Muted, style = AniCliType.Metadata)
        }
    }
}
