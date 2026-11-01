package io.github.marcomow.yuvomi.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marcomow.yuvomi.core.i18n.t

val LocalAvatarLoader = staticCompositionLocalOf<AvatarLoader?> { null }

/** Initials on the member's colour, replaced by their picture once loaded (SIGNIN-R18). */
@Composable
fun MemberAvatar(name: String, color: String?, picture: String?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val loader = LocalAvatarLoader.current
    var bitmap by remember(picture) { mutableStateOf<ImageBitmap?>(picture?.let { loader?.cached(it) }) }
    LaunchedEffect(picture) {
        if (picture != null && bitmap == null) bitmap = loader?.load(picture)
    }
    val background = Tokens.parseColor(color) ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                initials(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp,
                maxLines = 1,
            )
        }
    }
}

/** Up to [max] overlapping avatars, then `+N`. */
@Composable
fun AvatarStack(people: List<Triple<String, String?, String?>>, max: Int = 3, size: Dp = 24.dp) {
    if (people.isEmpty()) return
    val shown = people.take(max)
    val extra = people.size - shown.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            shown.forEachIndexed { i, (name, color, picture) ->
                MemberAvatar(
                    name, color, picture, size = size,
                    modifier = Modifier
                        .offset(x = (size * 0.7f) * i)
                        .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                )
            }
            Spacer(Modifier.width(size + (size * 0.7f) * (shown.size - 1)))
        }
        if (extra > 0) Text(" +$extra", style = MaterialTheme.typography.labelSmall)
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/** A module's glyph in its family tone (ADR 0002). */
@Composable
fun ModuleBadge(icon: ImageVector, tone: Color, size: Dp = 28.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(tone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(size * 0.62f))
    }
}

/** ADR 0007: cached content is shown with a quiet banner. */
@Composable
fun OfflineBanner(savedAt: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text(
                if (savedAt != null) t("offline.staleData", "time" to savedAt) else t("offline.banner"),
                style = MaterialTheme.typography.bodySmall,
                color = Yuvomi.colors.textSecondary,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Yuvomi.colors.danger, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(t("common.retry")) }
    }
}

/** A grouped surface like upstream's cards: rounded, on the paper background. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.5.dp,
    ) { content() }
}
