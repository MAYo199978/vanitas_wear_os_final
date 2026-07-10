package com.smarthealth.sync.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
//import androidx.wear.compose.material.darkColorScheme

//private val DarkColorScheme = darkColorScheme(
//    primary = Purple80,
//    secondary = PurpleGrey80,
//    tertiary = Pink80
//)

@Composable
fun WearAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
//        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
} 