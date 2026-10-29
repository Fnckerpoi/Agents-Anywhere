package com.agentsanywhere.embedded

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.agentsanywhere.app.ui.designsystem.LocalAAColors

internal val EditorialBackground = Color(0xFF17131B)
internal val EditorialSurface = Color(0xFF221D25)
internal val EditorialInk = Color(0xFFF0EDE5)
internal val EditorialMuted = Color(0xFFB1ABA9)
internal val EditorialLime = Color(0xFFDBF67A)

@Composable
internal fun EmbeddedTheme(content: @Composable () -> Unit) {
    val inherited = LocalAAColors.current
    val colors = inherited.copy(
        isDark = true, canvas = EditorialBackground, ink = EditorialInk, inkSoft = EditorialInk,
        muted = EditorialMuted, faint = EditorialMuted, border = Color(0xFF423A47),
        subtle = EditorialSurface, raisedSurface = EditorialSurface, dialogSurface = EditorialSurface,
        onRaisedSurface = EditorialInk, primaryAction = EditorialLime, onPrimaryAction = EditorialBackground,
        secondaryActionSurface = EditorialSurface, secondaryActionBorder = EditorialMuted,
        sessionMessageBubble = EditorialSurface, sessionMessageText = EditorialInk,
        sessionCodeSurface = EditorialBackground, sessionTimelineActivitySurface = EditorialSurface,
    )
    CompositionLocalProvider(LocalAAColors provides colors) {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = EditorialLime, onPrimary = EditorialBackground,
            background = EditorialBackground, onBackground = EditorialInk,
            surface = EditorialSurface, onSurface = EditorialInk,
            onSurfaceVariant = EditorialMuted, outline = Color(0xFF423A47),
        )) {
            Surface(Modifier.fillMaxSize(), color = EditorialBackground, content = content)
        }
    }
}
