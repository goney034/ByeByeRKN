package com.dostup.app.ui

import android.content.Intent
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.content.pm.PackageManager
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dostup.app.R
import com.dostup.app.data.SplitMode
import com.dostup.app.data.Store
import com.dostup.app.split.AppCatalog
import com.dostup.app.system.BackgroundWork
import com.dostup.app.update.UpdateChecker
import com.dostup.app.vpn.ExitInfo
import com.dostup.app.vpn.Traffic
import com.dostup.app.vpn.VpnController
import com.dostup.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun HomeScreen(
    vpn: VpnController,
    store: Store,
    updates: UpdateChecker,
    requestConnect: () -> Unit,
    openApps: () -> Unit,
    openSettings: () -> Unit,
) {
    val status by vpn.status.collectAsStateWithLifecycle()
    val traffic by vpn.traffic.collectAsStateWithLifecycle()
    val exit by vpn.exit.collectAsStateWithLifecycle()
    val newVersion by updates.newVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Only on phones whose makers stop background apps on their own; the fix is in Settings.
    var backgroundAtRisk by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        backgroundAtRisk = BackgroundWork.aggressiveMaker && !BackgroundWork.batteryUnrestricted(context)
    }

    val buttonFocus = remember { FocusRequester() }
    // A TV remote has nothing to start from: put it on the main button.
    LaunchedEffect(Unit) {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) runCatching { buttonFocus.requestFocus() }
    }
    val chips: @Composable ColumnScope.() -> Unit = {
        SplitChip(store, openApps)
        newVersion?.let { version ->
            Spacer(Modifier.height(8.dp))
            UpdateChip(version, updates.releasesUrl)
        }
        if (backgroundAtRisk) {
            Spacer(Modifier.height(8.dp))
            HomeChip(Icons.Outlined.Warning, stringResource(R.string.background_chip), openSettings)
        }
    }
    val button: @Composable (Dp) -> Unit = { size ->
        ConnectButton(status, Modifier.focusRequester(buttonFocus), size) {
            when (status) {
                VpnStatus.Disconnected, is VpnStatus.Failed -> requestConnect()
                else -> vpn.disconnect()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bgBase).statusBarsPadding()) {
        // TVs and phones turned sideways: side by side, since a column doesn't fit the height.
        if (maxWidth > maxHeight && maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    chips()
                    Spacer(Modifier.height(16.dp))
                    // A phone on its side is short: a smaller button leaves room for the status.
                    button(150.dp)
                    Spacer(Modifier.height(12.dp))
                    StatusLine(status)
                }
                Spacer(Modifier.width(32.dp))
                Box(Modifier.weight(1f)) { ServerCard(status, traffic, store, exit, sheet = false) }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                chips()
                Spacer(Modifier.weight(1f))
                button(190.dp)
                Spacer(Modifier.height(36.dp))
                StatusLine(status)
                Spacer(Modifier.weight(1f))
                ServerCard(status, traffic, store, exit)
            }
        }
    }
}

private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** Amnezia-style connect button: a thin ring with a soft glow, a spinning arc while connecting. */
@Composable
private fun ConnectButton(status: VpnStatus, modifier: Modifier = Modifier, diameter: Dp = 190.dp, onClick: () -> Unit) {
    val connected = status is VpnStatus.Connected
    val working = status is VpnStatus.Working
    val ringColor = remember { Animatable(Palette.textSecondary) }
    LaunchedEffect(connected) { ringColor.animateTo(if (connected) Palette.brand else Palette.textSecondary, tween(400)) }
    val sweep by animateFloatAsState(if (working) 160f else 360f, tween(500), label = "sweep")
    val glow by animateFloatAsState(if (connected) 1f else 0.35f, tween(600), label = "glow")

    // Spins only while connecting, so an idle screen doesn't redraw every frame.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(working) {
        while (working && isActive) {
            rotation.animateTo(rotation.value + 360f, tween(1000, easing = LinearEasing))
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A remote or keyboard needs to see where it is: the ring thickens and lights up.
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")

    Box(
        modifier
            .size(diameter)
            .scale(scale)
            .drawBehind {
                val stroke = (if (focused) 4.dp else 2.dp).toPx()
                val radius = (size.minDimension - stroke) / 2
                val color = if (focused && !connected) Palette.brand else ringColor.value
                // Soft glow on both sides of the ring, fading out over glowWidth.
                val glowWidth = 30.dp.toPx()
                val outer = radius + glowWidth
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        (radius - glowWidth) / outer to Color.Transparent,
                        radius / outer to color.copy(alpha = 0.22f * glow),
                        1f to Color.Transparent,
                        center = center,
                        radius = outer,
                    ),
                    radius = outer,
                )
                rotate(rotation.value) {
                    drawArc(
                        color = color,
                        startAngle = 100f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            // Clipped after drawing so the glow can spill outside the circle.
            .clip(RoundedCornerShape(50))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when (status) {
                is VpnStatus.Connected -> stringResource(R.string.action_disconnect)
                is VpnStatus.Working -> stringResource(R.string.status_connecting)
                else -> stringResource(R.string.action_connect)
            },
            color = if (connected) Palette.brand else Palette.textPrimary,
            fontSize = (20f * diameter.value / 190f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusLine(status: VpnStatus) {
    val now by produceState(System.currentTimeMillis(), status) {
        while (status is VpnStatus.Connected) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (text, color) = when (status) {
        VpnStatus.Disconnected -> stringResource(R.string.status_idle_hint) to Palette.textTertiary
        is VpnStatus.Working -> status.stage to Palette.textTertiary
        is VpnStatus.Failed -> status.message to Palette.danger
        is VpnStatus.Connected -> {
            val duration = formatDuration((now - status.since) / 1000)
            val line = stringResource(if (status.verified) R.string.status_connected else R.string.status_connected_unverified, duration)
            (if (status.degraded) line + "\n" + stringResource(R.string.status_degraded) else line) to Palette.brand
        }
    }
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp).heightIn(min = 44.dp),
    )
}

@Composable
private fun SplitChip(store: Store, onClick: () -> Unit) {
    val context = LocalContext.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val label by produceState(stringResource(R.string.split_tunnel), settings) {
        value = withContext(Dispatchers.IO) {
            val res = context.resources
            when (settings.splitMode) {
                SplitMode.ALL -> res.getString(R.string.split_chip_off)
                SplitMode.EXCLUDE -> AppCatalog.installedOnly(context, settings.excludedApps).size
                    .let { res.getQuantityString(R.plurals.split_chip_direct, it, it) }
                SplitMode.INCLUDE -> AppCatalog.installedOnly(context, settings.includedApps).size
                    .let { res.getQuantityString(R.plurals.split_chip_only, it, it) }
            }
        }
    }
    HomeChip(Icons.AutoMirrored.Outlined.List, label, onClick)
}

@Composable
private fun UpdateChip(version: String, url: String) {
    val context = LocalContext.current
    HomeChip(Icons.Outlined.Info, stringResource(R.string.update_available, version)) {
        // A phone can have no browser at all.
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
}

@Composable
private fun HomeChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bgSurface)
            .border(1.5.dp, if (focused) Palette.brand else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Palette.textTertiary)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.textSecondary, fontSize = 14.sp)
    }
}

/** Bottom sheet-like summary of the server, like Amnezia's server drawer. */
@Composable
private fun ServerCard(status: VpnStatus, traffic: Traffic, store: Store, exit: ExitInfo?, sheet: Boolean = true) {
    val connected = status as? VpnStatus.Connected
    val custom = store.customConfig != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(if (sheet) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(24.dp))
            .background(Palette.bgSurface)
            .padding(start = 20.dp, end = 20.dp, top = if (sheet) 10.dp else 22.dp, bottom = 18.dp),
    ) {
        // The handle belongs to a sheet at the bottom of the screen, not to a card beside the button.
        if (sheet) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Palette.bgPanel),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            if (custom) stringResource(R.string.server_custom) else "Cloudflare WARP",
            color = Palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        // Where traffic really leaves says more to a person than a server address.
        val language = LocalConfiguration.current.locales[0].language
        val place = connected?.let { exit?.city(language) ?: it.endpoint } ?: stringResource(R.string.server_auto)
        val protocol = if (connected?.masque != null) "MASQUE" else "AmneziaWG"
        val variant = connected?.masque?.title ?: connected?.obfuscation?.let { stringResource(it.titleRes) }
        Text(
            listOfNotNull(place, protocol, variant).joinToString(" · "),
            color = Palette.textTertiary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (connected != null) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stringResource(R.string.stat_ping), connected.rttMs?.let { stringResource(R.string.unit_ms, it) } ?: "—")
                Stat(stringResource(R.string.stat_received), formatBytes(traffic.rx))
                Stat(stringResource(R.string.stat_sent), formatBytes(traffic.tx))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Palette.textQuaternary, fontSize = 12.sp)
        Text(value, color = Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> stringResource(R.string.size_bytes, bytes)
    bytes < 1024 * 1024 -> stringResource(R.string.size_kb, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> stringResource(R.string.size_mb, bytes / (1024.0 * 1024))
    else -> stringResource(R.string.size_gb, bytes / (1024.0 * 1024 * 1024))
}

private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
