package io.lazaro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = AppleBlack,
    onPrimary = AppleWhite,
    secondary = AppleGray700,
    onSecondary = AppleWhite,
    background = AppleWhite,
    onBackground = AppleBlack,
    surface = AppleGray100,
    onSurface = AppleBlack,
    surfaceVariant = AppleGray200,
    onSurfaceVariant = AppleGray700,
    outline = AppleGray300,
    error = AppleGray900,
    onError = AppleWhite,
)

private val DarkColors = darkColorScheme(
    primary = AppleWhite,
    onPrimary = AppleBlack,
    secondary = AppleGray300,
    onSecondary = AppleBlack,
    background = AppleBlack,
    onBackground = AppleWhite,
    surface = AppleGray900,
    onSurface = AppleWhite,
    surfaceVariant = AppleGray700,
    onSurfaceVariant = AppleGray200,
    outline = AppleGray500,
    error = AppleGray100,
    onError = AppleBlack,
)

@Composable
fun LazaroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LazaroTypography,
        content = content,
    )
}
