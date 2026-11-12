package com.dostup.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** Lists stop growing here, so a TV or tablet doesn't stretch rows across the whole screen. */
val MaxContentWidth = 720.dp

/** Roomier than Miuix's default 16dp so dense settings stay readable on a dark background. */
val RowMargin = PaddingValues(horizontal = 20.dp, vertical = 18.dp)

/** Section header in the brand color, with breathing room above it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Palette.brand,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 8.dp, top = 28.dp, bottom = 10.dp),
    )
}

/** Cards a step lighter than the page so they read as separate surfaces. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = Palette.bgSurfaceAlt),
        content = content,
    )
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Palette.bgSheet)
}

/**
 * Bottom navigation item. Miuix's own item tints selected and unselected states with one color,
 * so it can't show the brand color only on the selected tab.
 */
@Composable
fun RowScope.NavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    val color by animateColorAsState(if (selected) Palette.brand else Palette.textQuaternary, label = "nav")
    val interaction = remember { MutableInteractionSource() }
    // No ripple on touch, but a remote or keyboard has to see which tab it is on.
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .weight(1f)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Palette.bgSurfaceAlt else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = color)
        Spacer(Modifier.height(3.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}
