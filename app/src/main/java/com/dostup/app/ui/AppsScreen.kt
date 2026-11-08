package com.dostup.app.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dostup.app.data.SplitMode
import com.dostup.app.data.Store
import com.dostup.app.split.AppCatalog
import com.dostup.app.split.AppEntry
import com.dostup.app.vpn.VpnController
import com.dostup.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val modes = listOf(SplitMode.ALL, SplitMode.EXCLUDE, SplitMode.INCLUDE)

@Composable
fun AppsScreen(vpn: VpnController, store: Store) {
    val context = LocalContext.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val status by vpn.status.collectAsStateWithLifecycle()
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { AppCatalog.launchable(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()

    val mode = settings.splitMode
    val selected = if (mode == SplitMode.INCLUDE) settings.includedApps else settings.excludedApps
    // Selected apps first, frozen per mode so rows don't jump while ticking boxes.
    val orderedApps = remember(apps, mode) { apps?.sortedByDescending { it.packageName in selected } }
    fun setSelected(next: Set<String>) {
        changed = true
        store.updateSettings { if (mode == SplitMode.INCLUDE) it.copy(includedApps = next) else it.copy(excludedApps = next) }
    }

    Box(Modifier.fillMaxSize().background(Palette.bgBase)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = "برنامه‌ها", largeTitle = "تونل تفکیکی", scrollBehavior = scrollBehavior)
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            ) {
                item {
                    TabRow(
                        tabs = listOf("همه", "به‌جز", "فقط"),
                        selectedTabIndex = modes.indexOf(mode),
                        onTabSelected = { index ->
                            changed = true
                            store.updateSettings { it.copy(splitMode = modes[index]) }
                        },
                        // Miuix rounds only the selected pill, leaving the track's corners square.
                        modifier = Modifier.clip(miuixShape(TabRowDefaults.TabRowCornerRadius)),
                        colors = TabRowDefaults.tabRowColors(
                            backgroundColor = Palette.bgSurfaceAlt,
                            selectedBackgroundColor = Palette.brandTint,
                            selectedContentColor = Palette.brand,
                        ),
                    )
                    Text(
                        when (mode) {
                            SplitMode.ALL -> "تمام ترافیک گوشی از طریق VPN عبور می‌کند."
                            SplitMode.EXCLUDE -> "برنامه‌های علامت‌خورده مستقیم کار می‌کنند و بقیه از طریق VPN. برای بانک‌ها، درگاه خدمات دولتی (Gosuslugi) و بازارچه‌های اینترنتی مناسب است."
                            SplitMode.INCLUDE -> "فقط برنامه‌های علامت‌خورده از طریق VPN می‌روند و بقیه مستقیم."
                        },
                        color = Palette.textTertiary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp),
                    )
                }

                if (changed && status is VpnStatus.Connected) {
                    item {
                        SectionCard(Modifier.padding(top = 20.dp)) {
                            SuperArrow(
                                title = "اعمال همین حالا",
                                titleColor = BasicComponentDefaults.titleColor(color = Palette.brand),
                                summary = "اتصال دوباره‌ی VPN با تنظیمات جدید",
                                insideMargin = RowMargin,
                                onClick = {
                                    changed = false
                                    vpn.reconnect()
                                },
                            )
                        }
                    }
                }

                if (mode == SplitMode.ALL) return@LazyColumn

                item {
                    SectionTitle("انتخاب سریع")
                    SectionCard {
                        if (mode == SplitMode.EXCLUDE) {
                            SuperArrow(
                                title = "افزودن سرویس‌های روسی",
                                summary = "بانک‌ها، Gosuslugi، بازارچه‌های اینترنتی، Yandex، VK",
                                insideMargin = RowMargin,
                                onClick = { setSelected(selected + AppCatalog.russianServices) },
                            )
                        } else {
                            SuperArrow(
                                title = "افزودن برنامه‌های مسدودشده",
                                summary = "Telegram, YouTube, Discord, Instagram, WhatsApp",
                                insideMargin = RowMargin,
                                onClick = { setSelected(selected + AppCatalog.blockedServices) },
                            )
                        }
                        RowDivider()
                        SuperArrow(title = "برداشتن همه‌ی علامت‌ها", insideMargin = RowMargin, onClick = { setSelected(emptySet()) })
                    }
                }

                val list = orderedApps
                if (list == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            InfiniteProgressIndicator()
                        }
                    }
                    return@LazyColumn
                }

                val visible = list
                    .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                item {
                    SectionTitle("برنامه‌ها · علامت‌خورده: ${list.count { it.packageName in selected }} از ${list.size}")
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "جست‌وجوی برنامه",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        backgroundColor = Palette.bgSurfaceAlt,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search, null,
                                Modifier.padding(start = 16.dp, end = 6.dp).size(20.dp),
                                tint = Palette.textQuaternary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
                    val checked = app.packageName in selected
                    val toggle = { setSelected(if (checked) selected - app.packageName else selected + app.packageName) }
                    AppRow(app, checked, first = index == 0, last = index == visible.lastIndex, onToggle = toggle)
                }
            }
        }
    }
}

/** One row of a visually continuous card built from separate lazy items. */
@Composable
private fun AppRow(app: AppEntry, checked: Boolean, first: Boolean, last: Boolean, onToggle: () -> Unit) {
    val top = if (first) 20.dp else 0.dp
    val bottom = if (last) 20.dp else 0.dp
    Column(
        Modifier
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(Palette.bgSurfaceAlt),
    ) {
        BasicComponent(
            title = app.label,
            summary = app.packageName,
            startAction = { AppIcon(app.packageName) },
            endActions = { Checkbox(state = ToggleableState(checked), onClick = onToggle) },
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            onClick = onToggle,
        )
        if (!last) RowDivider()
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState(IconCache.peek(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { IconCache.load(context, packageName) }
    }
    val bitmap = icon
    val modifier = Modifier.padding(end = 14.dp).size(40.dp)
    if (bitmap != null) Image(bitmap, null, modifier) else Spacer(modifier)
}

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
    }.getOrNull()?.also { cache.put(packageName, it) }
}
