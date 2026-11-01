package com.youtubly.app.presentation.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.youtubly.app.presentation.theme.BrandGradientH

data class NavTab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Floating glass pill: the selected tab expands into a gradient capsule with its label,
 * the icon springs up in scale, others collapse to icon-only.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selectedRoute: String?,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = if (tabs.size > 5) 8.dp else 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .shadow(24.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(32.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                NavPill(tab, selected = tab.route == selectedRoute, compact = tabs.size > 4, dense = tabs.size > 5, onClick = { onSelect(tab) })
            }
        }
    }
}

@Composable
private fun NavPill(tab: NavTab, selected: Boolean, compact: Boolean, dense: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (selected) 1.12f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "iconScale"
    )
    val hPad by animateDpAsState(
        when {
            dense -> if (selected) 12.dp else 8.dp   // six tabs: keep the bar inside a 360 dp wide phone
            selected -> if (compact) 16.dp else 18.dp
            compact -> 10.dp
            else -> 14.dp
        },
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "hPad"
    )
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.background(BrandGradientH) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(tab.label, color = contentColor, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
