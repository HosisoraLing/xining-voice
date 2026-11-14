package com.hosira.oopznative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF7C6CFF),
    onPrimary = Color.White,
    surface = Color(0xFF0B1020),
    background = Color(0xFF0B1020),
    onSurface = Color(0xFFE8EAF6)
)

@Composable
fun OopzNativeTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, content = content)
