package com.dostup.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dostup.app.R
import com.dostup.app.autoapp.AutoApps
import com.dostup.app.split.AppCatalog
import com.dostup.app.split.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/** Fork: the apps that switch the VPN on while they are open. */
@Composable
fun AutoAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { AppCatalog.launchable(context) } }
    var selected by remember { mutableStateOf(AutoApps.apps(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    // Chosen apps first, frozen while the screen is open so rows don't jump when ticked.
    val ordered = remember(apps) { apps?.sortedByDescending { it.packageName in selected } }

    fun toggle(pkg: String) {
        selected = if (pkg in selected) selected - pkg else selected + pkg
        AutoApps.setApps(context, selected)
        AutoApps.sync(context)
    }

    Column(Modifier.fillMaxSize().background(Palette.bgBase).statusBarsPadding()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.autoapp_choose), color = Palette.textPrimary, fontSize = 20.sp)
            Text(stringResource(R.string.autoapp_choose_hint), color = Palette.textTertiary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            TextButton(stringResource(R.string.log_back), onBack, Modifier.fillMaxWidth())
        }
        val list = ordered
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() }
            return@Column
        }
        val visible = list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
        LazyColumn(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = MaxContentWidth).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.search_apps),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    backgroundColor = Palette.bgSurfaceAlt,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.padding(start = 16.dp, end = 6.dp).size(20.dp), tint = Palette.textQuaternary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
                AppRow(app, app.packageName in selected, first = index == 0, last = index == visible.lastIndex, onToggle = { toggle(app.packageName) })
            }
        }
    }
}
