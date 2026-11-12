package com.dostup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import com.dostup.app.masque.MasqueRoute

/**
 * Drives the VPN from adb in debug builds, without touching the screen:
 *
 *   adb shell am broadcast -a com.dostup.app.DEBUG -p com.dostup.app --es cmd connect|disconnect|failover|survey|diagnose
 *
 * The screen can be turned for layout checks with --es cmd orientation --es value landscape|portrait|auto,
 * and, to try MASQUE without waiting for AmneziaWG to fail, with HTTP3, HTTP2 or HTTP3_PLAIN:
 *
 *   adb shell am broadcast -a com.dostup.app.DEBUG -p com.dostup.app --es cmd masque --es route HTTP2
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val vpn = DostupApp.instance.vpn
        val cmd = intent.getStringExtra("cmd")
        Log.i("Dostup/Debug", "command: $cmd")
        when (cmd) {
            "connect" -> vpn.connect()
            "disconnect" -> vpn.disconnect()
            "failover" -> vpn.debugFailover()
            "survey" -> vpn.debugSurvey()
            "diagnose" -> vpn.debugDiagnose()
            "orientation" -> DostupApp.instance.debugOrientation.value = when (intent.getStringExtra("value")) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            "masque" -> vpn.debugMasque(
                MasqueRoute.valueOf(intent.getStringExtra("route") ?: MasqueRoute.HTTP3.name),
                intent.getStringExtra("endpoint"),
            )
        }
    }
}
