package com.dostup.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dostup.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * Fork: a hidden log page. The log is what this app wrote to the system log (logcat), which an app
 * may read about itself without any permission.
 */
object DevLog {
    private const val PREFS = "dostup"
    private const val KEY = "logEnabled"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** The last lines this process logged, oldest first. */
    fun read(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat: ${it.message}" }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    BackHandler(onBack = onBack)
    LaunchedEffect(refresh) {
        text = withContext(Dispatchers.IO) { DevLog.read() }
        scroll.scrollTo(scroll.maxValue)
    }
    Column(Modifier.fillMaxSize().background(Palette.bgBase).statusBarsPadding().padding(12.dp)) {
        Text(stringResource(R.string.log_title), color = Palette.textPrimary, fontSize = 20.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.log_back), onBack, Modifier.weight(1f))
            TextButton(stringResource(R.string.log_refresh), { refresh++ }, Modifier.weight(1f))
            TextButton(stringResource(R.string.log_copy), {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
                Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f))
            TextButton(stringResource(R.string.log_share), {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_title)))
            }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
        Text(
            text = text.ifBlank { stringResource(R.string.log_empty) },
            color = Palette.textTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        )
    }
}
