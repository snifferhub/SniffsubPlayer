package com.sniffsub.sniff_sub_player.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val SniffBlue = Color(0xFF0088FF)

@Composable
fun GlassNavigationBar(
    backdrop: Backdrop,
    browserSelected: Boolean,
    sniffEnabled: Boolean,
    onBrowser: () -> Unit,
    onSettings: () -> Unit,
    onSniff: () -> Unit,
    modifier: Modifier = Modifier,
    darkBackdrop: Boolean? = null,
) {
    val surface = when (darkBackdrop) {
        true -> Color.Black
        false -> Color.White
        null -> MaterialTheme.colorScheme.surface
    }
    val unselectedTint = when (darkBackdrop) {
        true -> Color.White
        false -> Color(0xFF292B30)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glassTint = surface.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.16f else 0.30f)
    Row(
        modifier = modifier.height(64.dp).testTag("bottom_navigation"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(16.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = { drawRect(glassTint) },
                )
                .padding(5.dp)
                .selectableGroup(),
        ) {
            val tabWidth = maxWidth / 2
            val selectionOffset by animateDpAsState(
                targetValue = if (browserSelected) 0.dp else tabWidth,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                label = "glassSelection",
            )
            Box(
                Modifier.offset(x = selectionOffset).width(tabWidth).height(54.dp)
                    .background(SniffBlue.copy(alpha = 0.12f), CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            )
            Row(Modifier.fillMaxSize()) {
                GlassTab(
                    label = "浏览器", icon = Icons.Default.Language, selected = browserSelected,
                    unselectedTint = unselectedTint,
                    onClick = onBrowser, modifier = Modifier.weight(1f).testTag("browser_tab"),
                )
                GlassTab(
                    label = "设置", icon = Icons.Default.Settings, selected = !browserSelected,
                    unselectedTint = unselectedTint,
                    onClick = onSettings, modifier = Modifier.weight(1f).testTag("settings_tab"),
                )
            }
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 450f),
            label = "sniffPress",
        )
        IconButton(
            onClick = onSniff,
            enabled = sniffEnabled,
            interactionSource = interaction,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        blur(4.dp.toPx())
                        lens(16.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(SniffBlue.copy(alpha = if (sniffEnabled) 0.65f else 0.30f))
                        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)))
                    },
                )
                .testTag("sniff_button"),
        ) {
            Icon(
                Icons.Default.Radar,
                contentDescription = "嗅探媒体资源",
                tint = Color.White.copy(alpha = if (sniffEnabled) 1f else 0.6f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun GlassTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    unselectedTint: Color,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) SniffBlue else unselectedTint,
        label = "glassTabTint",
    )
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}
