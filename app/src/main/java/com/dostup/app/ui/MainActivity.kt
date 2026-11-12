package com.dostup.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.dostup.app.DostupApp
import com.dostup.app.R
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.Scaffold
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var connectRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Leaves the splash theme before anything is drawn.
        setTheme(R.style.Theme_Dostup)
        super.onCreate(savedInstanceState)
        // The app is dark-only, so the system bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Not on a rotation: the request was already handled, and the user may have disconnected since.
        connectRequested = savedInstanceState == null && isConnectRequest(intent)
        val app = DostupApp.instance
        setContent {
            DostupTheme {
                DostupRoot(app, connectRequested) { connectRequested = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isConnectRequest(intent)) connectRequested = true
    }

    companion object {
        private const val EXTRA_CONNECT = "connect"

        /**
         * Known only inside this process. The launcher activity has to be exported, so without
         * the token any app on the phone could start the VPN by passing the extra.
         */
        private val connectToken = UUID.randomUUID().toString()

        /** What the quick settings tile opens when VPN permission still has to be granted. */
        fun connectIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CONNECT, connectToken)

        private fun isConnectRequest(intent: Intent) = intent.getStringExtra(EXTRA_CONNECT) == connectToken
    }
}

@Composable
private fun DostupRoot(app: DostupApp, connectRequested: Boolean, onConnectHandled: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            app.vpn.connect()
        } else {
            Toast.makeText(context, resources.getString(R.string.toast_vpn_permission_needed), Toast.LENGTH_LONG).show()
        }
    }
    val proceedToConnect = {
        val intent = VpnService.prepare(context)
        if (intent == null) app.vpn.connect() else permission.launch(intent)
    }
    // Asked once, before the first connection; the VPN works either way, only the status icon differs.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { proceedToConnect() }
    val requestConnect = {
        val mustAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.store.askedNotifications &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (mustAsk) {
            app.store.askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceedToConnect()
        }
    }

    val orientation by app.debugOrientation.collectAsState()
    LaunchedEffect(orientation) { orientation?.let { (context as? Activity)?.requestedOrientation = it } }
    LaunchedEffect(Unit) {
        app.updates.checkIfDue()
        app.catalog.refreshIfDue()
    }
    LaunchedEffect(connectRequested) {
        if (connectRequested) {
            onConnectHandled()
            requestConnect()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(color = Palette.bgBase) {
                NavItem(tab == 0, { tab = 0 }, Icons.Outlined.Home, stringResource(R.string.nav_home))
                NavItem(tab == 1, { tab = 1 }, Icons.AutoMirrored.Outlined.List, stringResource(R.string.nav_apps))
                NavItem(tab == 2, { tab = 2 }, Icons.Outlined.Settings, stringResource(R.string.nav_settings))
            }
        },
    ) { padding ->
        // Screens draw under the status bar themselves; only the navigation bar is reserved here.
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                0 -> HomeScreen(app.vpn, app.store, app.updates, requestConnect, openApps = { tab = 1 }, openSettings = { tab = 2 })
                1 -> AppsScreen(app.vpn, app.store)
                else -> SettingsScreen(app.vpn, app.store)
            }
        }
    }
}
