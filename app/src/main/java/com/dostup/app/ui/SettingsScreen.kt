package com.dostup.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dostup.app.BuildConfig
import com.dostup.app.R
import com.dostup.app.awg.Obfuscation
import com.dostup.app.awg.QuicMask
import com.dostup.app.data.DnsOption
import com.dostup.app.data.Store
import com.dostup.app.share.ShareProxy
import com.dostup.app.share.TetherInterfaces
import com.dostup.app.system.BackgroundWork
import com.dostup.app.tile.ToggleTileService
import com.dostup.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.config.Config
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

private const val SHARE_REFRESH_MS = 3_000L

private val ENDPOINT_PATTERN = Regex("""^(\[[0-9a-fA-F:]+]|[\w.\-]+):\d{1,5}$""")

@Composable
fun SettingsScreen(vpn: VpnController, store: Store) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle()
    var customConfig by remember { mutableStateOf(store.customConfig) }
    var endpointText by rememberSaveable { mutableStateOf(settings.manualEndpoint ?: "162.159.192.1:2408") }
    var manualEndpoint by remember { mutableStateOf(settings.manualEndpoint != null) }
    var tileAdded by remember { mutableStateOf(store.tileAdded) }
    var confirmReset by remember { mutableStateOf(false) }
    var editSni by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var editWarpPlus by remember { mutableStateOf(false) }
    var warpPlusKey by remember { mutableStateOf(store.warpPlusKey) }
    var batteryOk by remember { mutableStateOf(BackgroundWork.batteryUnrestricted(context)) }
    // The answer comes from a system screen: read it again whenever the user is back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = BackgroundWork.batteryUnrestricted(context) }
    val autostart = remember { BackgroundWork.autostartIntent(context) }
    val makerBattery = remember { BackgroundWork.makerBatteryIntent(context) }
    var shareAddresses by remember { mutableStateOf(emptyList<String>()) }
    // A hotspot can be switched on while this screen is open.
    LaunchedEffect(settings.shareVpn) {
        while (settings.shareVpn) {
            shareAddresses = withContext(Dispatchers.IO) { TetherInterfaces.addresses(context).mapNotNull { it.hostAddress } }
            delay(SHARE_REFRESH_MS)
        }
    }
    val scrollBehavior = MiuixScrollBehavior()

    fun withConfig(action: (String) -> Unit) {
        scope.launch {
            val text = runCatching { withContext(Dispatchers.IO) { vpn.exportConfig() } }
            text.onSuccess(action).onFailure { toast(context, resources.getString(R.string.toast_config_failed, it.message.orEmpty())) }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.bgBase)) {
        TopAppBar(title = stringResource(R.string.nav_settings), scrollBehavior = scrollBehavior)
        LazyColumn(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = MaxContentWidth).fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        ) {
            if (customConfig != null) {
                item {
                    SectionCard(Modifier.padding(top = 12.dp)) {
                        BasicComponent(
                            title = stringResource(R.string.custom_in_use),
                            summary = stringResource(R.string.custom_in_use_summary),
                            insideMargin = RowMargin,
                        )
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.back_to_warp),
                            titleColor = BasicComponentDefaults.titleColor(color = Palette.brand),
                            insideMargin = RowMargin,
                            onClick = {
                                store.customConfig = null
                                customConfig = null
                            },
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_shade))
                SectionCard {
                    SuperArrow(
                        title = stringResource(R.string.tile_title),
                        summary = if (tileAdded) stringResource(R.string.tile_added) else stringResource(R.string.tile_add),
                        insideMargin = RowMargin,
                        onClick = {
                            if (tileAdded) return@SuperArrow
                            val asked = ToggleTileService.requestAdd(context) { added ->
                                tileAdded = added
                                if (added) toast(context, resources.getString(R.string.toast_tile_added))
                            }
                            if (!asked) toast(context, resources.getString(R.string.toast_tile_manual))
                        },
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_background))
                SectionCard {
                    SuperArrow(
                        title = stringResource(R.string.battery_unrestricted),
                        summary = stringResource(if (batteryOk) R.string.battery_unrestricted_on else R.string.battery_unrestricted_off),
                        insideMargin = RowMargin,
                        onClick = {
                            if (batteryOk) BackgroundWork.openBatterySettings(context) else BackgroundWork.requestBatteryUnrestricted(context)
                        },
                    )
                    autostart?.let { intent ->
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.autostart),
                            summary = stringResource(R.string.autostart_summary),
                            insideMargin = RowMargin,
                            onClick = { BackgroundWork.start(context, intent) },
                        )
                    }
                    makerBattery?.let { intent ->
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.maker_battery),
                            summary = stringResource(R.string.maker_battery_summary),
                            insideMargin = RowMargin,
                            onClick = { BackgroundWork.start(context, intent) },
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_share))
                SectionCard {
                    SuperSwitch(
                        title = stringResource(R.string.share_vpn),
                        summary = stringResource(R.string.share_vpn_summary),
                        checked = settings.shareVpn,
                        onCheckedChange = { on -> store.updateSettings { it.copy(shareVpn = on) } },
                        insideMargin = RowMargin,
                    )
                    if (settings.shareVpn) {
                        RowDivider()
                        BasicComponent(
                            title = stringResource(R.string.share_address),
                            summary = if (shareAddresses.isEmpty()) {
                                stringResource(R.string.share_no_hotspot)
                            } else {
                                shareAddresses.joinToString("\n") { "$it:${ShareProxy.DEFAULT_PORT}" } + "\n" + stringResource(R.string.share_protocols)
                            },
                            insideMargin = RowMargin,
                        )
                        RowDivider()
                        BasicComponent(
                            title = stringResource(R.string.share_how),
                            summary = stringResource(R.string.share_how_summary),
                            insideMargin = RowMargin,
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_config))
                SectionCard {
                    SuperArrow(
                        title = stringResource(R.string.share_config),
                        summary = stringResource(R.string.share_config_summary),
                        insideMargin = RowMargin,
                        onClick = { withConfig { shareConfig(context, it) } },
                    )
                    RowDivider()
                    SuperArrow(
                        title = stringResource(R.string.copy_config),
                        insideMargin = RowMargin,
                        onClick = {
                            withConfig {
                                copyToClipboard(context, it)
                                toast(context, resources.getString(R.string.toast_config_copied))
                            }
                        },
                    )
                    RowDivider()
                    SuperArrow(
                        title = stringResource(R.string.import_config),
                        summary = stringResource(R.string.import_config_summary),
                        insideMargin = RowMargin,
                        onClick = {
                            val text = readClipboard(context)
                            val error = validateConfig(context, text)
                            if (error != null) {
                                toast(context, error)
                            } else {
                                store.customConfig = text
                                customConfig = text
                                toast(context, resources.getString(R.string.toast_config_imported))
                            }
                        },
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_advanced))
                SectionCard {
                    SuperArrow(
                        title = if (showAdvanced) stringResource(R.string.advanced_hide) else stringResource(R.string.advanced_show),
                        summary = stringResource(R.string.advanced_summary),
                        insideMargin = RowMargin,
                        onClick = { showAdvanced = !showAdvanced },
                    )
                }
            }

            if (showAdvanced) {
                item {
                    SectionTitle(stringResource(R.string.section_connection))
                    SectionCard {
                        val obfuscations = listOf<Obfuscation?>(null) + Obfuscation.entries
                        SuperDropdown(
                            title = stringResource(R.string.disguise),
                            summary = settings.obfuscation?.let { stringResource(it.summaryRes) } ?: stringResource(R.string.disguise_auto_summary),
                            items = obfuscations.map { it?.let { o -> stringResource(o.titleRes) } ?: stringResource(R.string.auto) },
                            selectedIndex = obfuscations.indexOf(settings.obfuscation),
                            insideMargin = RowMargin,
                            onSelectedIndexChange = { index -> store.updateSettings { it.copy(obfuscation = obfuscations[index]) } },
                        )
                        RowDivider()
                        SuperDropdown(
                            title = stringResource(R.string.server),
                            summary = if (manualEndpoint) stringResource(R.string.server_manual_summary) else stringResource(R.string.server_auto_summary),
                            items = listOf(stringResource(R.string.auto), stringResource(R.string.manual)),
                            selectedIndex = if (manualEndpoint) 1 else 0,
                            insideMargin = RowMargin,
                            onSelectedIndexChange = { index ->
                                manualEndpoint = index == 1
                                store.updateSettings {
                                    it.copy(manualEndpoint = if (manualEndpoint && ENDPOINT_PATTERN.matches(endpointText)) endpointText else null)
                                }
                            },
                        )
                        if (manualEndpoint) {
                            TextField(
                                value = endpointText,
                                onValueChange = { value ->
                                    endpointText = value.trim()
                                    if (ENDPOINT_PATTERN.matches(endpointText)) {
                                        store.updateSettings { it.copy(manualEndpoint = endpointText) }
                                    }
                                },
                                label = stringResource(R.string.address_port),
                                singleLine = true,
                                backgroundColor = Palette.bgSheet,
                                borderColor = if (ENDPOINT_PATTERN.matches(endpointText)) Palette.brand else Palette.danger,
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            )
                        }
                        RowDivider()
                        SuperDropdown(
                            title = stringResource(R.string.dns),
                            summary = settings.dns.servers.take(2).joinToString(),
                            items = DnsOption.entries.map { stringResource(it.titleRes) },
                            selectedIndex = settings.dns.ordinal,
                            insideMargin = RowMargin,
                            onSelectedIndexChange = { index -> store.updateSettings { it.copy(dns = DnsOption.entries[index]) } },
                        )
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.quic_site),
                            summary = settings.quicSni.ifBlank { stringResource(R.string.quic_site_random) },
                            insideMargin = RowMargin,
                            onClick = { editSni = true },
                        )
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.repick_server),
                            summary = stringResource(R.string.repick_server_summary),
                            insideMargin = RowMargin,
                            onClick = {
                                vpn.forgetServer()
                                toast(context, resources.getString(R.string.toast_repick_server))
                            },
                        )
                    }
                }

                item {
                    SectionTitle(stringResource(R.string.section_account))
                    SectionCard {
                        SuperArrow(
                            title = stringResource(R.string.warp_plus_key),
                            summary = warpPlusKey?.let(::maskKey) ?: stringResource(R.string.warp_plus_none),
                            insideMargin = RowMargin,
                            onClick = { editWarpPlus = true },
                        )
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.reregister),
                            summary = if (store.account != null) stringResource(R.string.reregister_summary) else stringResource(R.string.reregister_summary_none),
                            insideMargin = RowMargin,
                            onClick = { confirmReset = true },
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.section_about))
                SectionCard {
                    BasicComponent(
                        title = "ByeByeRKN ${BuildConfig.VERSION_NAME}",
                        summary = "AmneziaWG ${vpn.awgVersion}",
                        insideMargin = RowMargin,
                    )
                    // Android 13+ keeps a language per app; older versions follow the system language.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        RowDivider()
                        SuperArrow(
                            title = stringResource(R.string.language),
                            summary = currentLanguageName(),
                            insideMargin = RowMargin,
                            onClick = { openLanguageSettings(context) },
                        )
                    }
                    RowDivider()
                    BasicComponent(
                        title = stringResource(R.string.licenses),
                        summary = stringResource(R.string.licenses_summary),
                        insideMargin = RowMargin,
                    )
                }
            }
        }
    }

    WarpPlusDialog(
        show = editWarpPlus,
        current = warpPlusKey.orEmpty(),
        onDismiss = { editWarpPlus = false },
        onSave = { value ->
            editWarpPlus = false
            scope.launch {
                val message = if (value.isBlank()) {
                    vpn.forgetWarpPlusKey()
                    resources.getString(R.string.warp_plus_forgotten)
                } else {
                    toast(context, resources.getString(R.string.warp_plus_checking))
                    vpn.setWarpPlusKey(value)
                }
                warpPlusKey = store.warpPlusKey
                toast(context, message)
            }
        },
    )

    SniDialog(
        show = editSni,
        current = settings.quicSni,
        onDismiss = { editSni = false },
        onSave = { value ->
            editSni = false
            store.updateSettings { it.copy(quicSni = value.trim()) }
        },
    )

    SuperDialog(
        show = confirmReset,
        title = stringResource(R.string.reregister_dialog_title),
        summary = stringResource(R.string.reregister_dialog_text),
        onDismissRequest = { confirmReset = false },
    ) {
        DialogButtons(
            confirm = stringResource(R.string.reregister_confirm),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                vpn.resetAccount()
                toast(context, resources.getString(R.string.toast_reregister))
            },
        )
    }
}

@Composable
private fun WarpPlusDialog(show: Boolean, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(show) { mutableStateOf(current) }
    SuperDialog(
        show = show,
        title = stringResource(R.string.warp_plus_key),
        summary = stringResource(R.string.warp_plus_dialog_text),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            label = "xxxxxxxx-xxxxxxxx-xxxxxxxx",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        DialogButtons(confirm = stringResource(R.string.save), onDismiss = onDismiss, onConfirm = { onSave(draft) })
    }
}

/** Enough of the key to recognise it, not to copy it from a screenshot. */
private fun maskKey(key: String) = "${key.take(4)}…${key.takeLast(4)}"

@Composable
private fun SniDialog(show: Boolean, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(show) { mutableStateOf(current) }
    SuperDialog(
        show = show,
        title = stringResource(R.string.quic_site),
        summary = stringResource(R.string.quic_site_hint, QuicMask.defaultSni.take(3).joinToString()),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            label = "ya.ru",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        DialogButtons(confirm = stringResource(R.string.save), onDismiss = onDismiss, onConfirm = { onSave(draft) })
    }
}

@Composable
private fun DialogButtons(confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun validateConfig(context: Context, text: String?): String? {
    if (text.isNullOrBlank()) return context.getString(R.string.err_clipboard_empty)
    return try {
        Config.parse(BufferedReader(StringReader(text)))
        null
    } catch (e: Exception) {
        context.getString(R.string.err_not_a_config, e.message.orEmpty())
    }
}

@Composable
private fun currentLanguageName(): String {
    val locale = LocalConfiguration.current.locales[0]
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

private fun openLanguageSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}

private fun shareConfig(context: Context, text: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "byebyerkn.conf").apply { writeText(text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_config)))
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("byebyerkn.conf", text))
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
