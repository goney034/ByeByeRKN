package com.dostup.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dostup.app.BuildConfig
import com.dostup.app.awg.Obfuscation
import com.dostup.app.awg.QuicMask
import com.dostup.app.data.DnsOption
import com.dostup.app.data.Store
import com.dostup.app.tile.ToggleTileService
import com.dostup.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
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
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

private val ENDPOINT_PATTERN = Regex("""^(\[[0-9a-fA-F:]+]|[\w.\-]+):\d{1,5}$""")

@Composable
fun SettingsScreen(vpn: VpnController, store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle()
    var customConfig by remember { mutableStateOf(store.customConfig) }
    var endpointText by rememberSaveable { mutableStateOf(settings.manualEndpoint ?: "162.159.192.1:2408") }
    var manualEndpoint by remember { mutableStateOf(settings.manualEndpoint != null) }
    var tileAdded by remember { mutableStateOf(store.tileAdded) }
    var confirmReset by remember { mutableStateOf(false) }
    var editSni by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()

    fun withConfig(action: (String) -> Unit) {
        scope.launch {
            val text = runCatching { withContext(Dispatchers.IO) { vpn.exportConfig() } }
            text.onSuccess(action).onFailure { toast(context, "دریافت کانفیگ ناموفق بود: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.bgBase)) {
        TopAppBar(title = "تنظیمات", scrollBehavior = scrollBehavior)
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        ) {
            if (customConfig != null) {
                item {
                    SectionCard(Modifier.padding(top = 12.dp)) {
                        BasicComponent(
                            title = "کانفیگ سفارشی در حال استفاده است",
                            summary = "WARP، پنهان‌سازی و انتخاب سرور غیرفعال هستند",
                            insideMargin = RowMargin,
                        )
                        RowDivider()
                        SuperArrow(
                            title = "بازگشت به WARP",
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
                SectionTitle("پرده‌ی اعلان‌ها")
                SectionCard {
                    SuperArrow(
                        title = "کاشی «ByeByeRKN»",
                        summary = if (tileAdded) "به پرده‌ی اعلان اضافه شد" else "افزودن به پنل تنظیمات سریع",
                        insideMargin = RowMargin,
                        onClick = {
                            if (tileAdded) return@SuperArrow
                            val asked = ToggleTileService.requestAdd(context) { added ->
                                tileAdded = added
                                if (added) toast(context, "کاشی به پرده‌ی اعلان اضافه شد")
                            }
                            if (!asked) toast(context, "پرده‌ی اعلان را باز کنید، روی مداد بزنید و کاشی «ByeByeRKN» را به پنل بکشید")
                        },
                    )
                }
            }

            item {
                SectionTitle("کانفیگ برای AmneziaWG")
                SectionCard {
                    SuperArrow(
                        title = "اشتراک‌گذاری کانفیگ",
                        summary = "فایل byebyerkn.conf",
                        insideMargin = RowMargin,
                        onClick = { withConfig { shareConfig(context, it) } },
                    )
                    RowDivider()
                    SuperArrow(
                        title = "کپی کانفیگ",
                        insideMargin = RowMargin,
                        onClick = {
                            withConfig {
                                copyToClipboard(context, it)
                                toast(context, "کانفیگ کپی شد")
                            }
                        },
                    )
                    RowDivider()
                    SuperArrow(
                        title = "وارد کردن از کلیپ‌بورد",
                        summary = "سرور شخصی به‌جای WARP",
                        insideMargin = RowMargin,
                        onClick = {
                            val text = readClipboard(context)
                            val error = validateConfig(text)
                            if (error != null) {
                                toast(context, error)
                            } else {
                                store.customConfig = text
                                customConfig = text
                                toast(context, "کانفیگ وارد شد")
                            }
                        },
                    )
                }
            }

            item {
                SectionTitle("برای کاربران حرفه‌ای")
                SectionCard {
                    SuperArrow(
                        title = if (showAdvanced) "پنهان کردن تنظیمات دستی" else "نمایش تنظیمات دستی",
                        summary = "معمولاً لازم نیست: برنامه همه‌چیز را خودکار انتخاب می‌کند",
                        insideMargin = RowMargin,
                        onClick = { showAdvanced = !showAdvanced },
                    )
                }
            }

            if (showAdvanced) {
                item {
                    SectionTitle("اتصال")
                    SectionCard {
                        val obfuscations = listOf<Obfuscation?>(null) + Obfuscation.entries
                        SuperDropdown(
                            title = "پنهان‌سازی",
                            summary = settings.obfuscation?.summary ?: "متناسب با شبکه‌ی شما انتخاب می‌شود",
                            items = obfuscations.map { it?.title ?: "خودکار" },
                            selectedIndex = obfuscations.indexOf(settings.obfuscation),
                            insideMargin = RowMargin,
                            onSelectedIndexChange = { index -> store.updateSettings { it.copy(obfuscation = obfuscations[index]) } },
                        )
                        RowDivider()
                        SuperDropdown(
                            title = "سرور",
                            summary = if (manualEndpoint) "آدرس WARP شخصی" else "خودکار انتخاب می‌شود",
                            items = listOf("خودکار", "دستی"),
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
                                label = "آدرس:پورت",
                                singleLine = true,
                                backgroundColor = Palette.bgSheet,
                                borderColor = if (ENDPOINT_PATTERN.matches(endpointText)) Palette.brand else Palette.danger,
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            )
                        }
                        RowDivider()
                        SuperDropdown(
                            title = "DNS",
                            summary = settings.dns.servers.take(2).joinToString(),
                            items = DnsOption.entries.map { it.title },
                            selectedIndex = settings.dns.ordinal,
                            insideMargin = RowMargin,
                            onSelectedIndexChange = { index -> store.updateSettings { it.copy(dns = DnsOption.entries[index]) } },
                        )
                        RowDivider()
                        SuperArrow(
                            title = "سایت برای ماسک QUIC",
                            summary = settings.quicSni.ifBlank { "یک سایت محبوب تصادفی" },
                            insideMargin = RowMargin,
                            onClick = { editSni = true },
                        )
                        RowDivider()
                        SuperArrow(
                            title = "انتخاب دوباره‌ی سرور",
                            summary = "در اتصال بعدی",
                            insideMargin = RowMargin,
                            onClick = {
                                vpn.forgetServer()
                                toast(context, "در اتصال بعدی سرور دوباره انتخاب می‌شود")
                            },
                        )
                    }
                }

                item {
                    SectionTitle("حساب WARP")
                    SectionCard {
                        SuperArrow(
                            title = "ثبت‌نام دوباره",
                            summary = if (store.account != null) "کلید و آدرس WARP جدید" else "ثبت‌نام در اولین اتصال انجام می‌شود",
                            insideMargin = RowMargin,
                            onClick = { confirmReset = true },
                        )
                    }
                }
            }

            item {
                SectionTitle("درباره‌ی برنامه")
                SectionCard {
                    BasicComponent(
                        title = "ByeByeRKN ${BuildConfig.VERSION_NAME}",
                        summary = "AmneziaWG ${vpn.awgVersion}",
                        insideMargin = RowMargin,
                    )
                    RowDivider()
                    BasicComponent(
                        title = "مجوزها",
                        summary = "amneziawg-android و amneziawg-go (Apache-2.0 / MIT)، بر پایه‌ی WireGuard®. " +
                            "ارتباطی با Cloudflare ندارد؛ WARP سرویس Cloudflare, Inc. است.",
                        insideMargin = RowMargin,
                    )
                }
            }
        }
    }

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
        title = "ثبت‌نام جدید",
        summary = "کلید و آدرس WARP جدیدی ساخته می‌شود. کانفیگ‌های صادرشده‌ی قبلی تا زمانی که Cloudflare آن‌ها را غیرفعال نکند کار می‌کنند.",
        onDismissRequest = { confirmReset = false },
    ) {
        DialogButtons(
            confirm = "بازنشانی",
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                vpn.resetAccount()
                toast(context, "ثبت‌نام جدید در اتصال بعدی انجام می‌شود")
            },
        )
    }
}

@Composable
private fun SniDialog(show: Boolean, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(show) { mutableStateOf(current) }
    SuperDialog(
        show = show,
        title = "سایت برای ماسک QUIC",
        summary = "بسته‌ی اول شبیه یک درخواست HTTP/3 به این سایت خواهد بود. " +
            "اگر خالی باشد، یکی از ${QuicMask.defaultSni.take(3).joinToString()} و سایت‌های دیگر به‌صورت تصادفی انتخاب می‌شود.",
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
        DialogButtons(confirm = "ذخیره", onDismiss = onDismiss, onConfirm = { onSave(draft) })
    }
}

@Composable
private fun DialogButtons(confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = "انصراف", onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun validateConfig(text: String?): String? {
    if (text.isNullOrBlank()) return "متنی در کلیپ‌بورد نیست"
    return try {
        Config.parse(BufferedReader(StringReader(text)))
        null
    } catch (e: Exception) {
        "این شبیه کانفیگ WireGuard/AmneziaWG نیست: ${e.message}"
    }
}

private fun shareConfig(context: Context, text: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "byebyerkn.conf").apply { writeText(text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری کانفیگ"))
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
