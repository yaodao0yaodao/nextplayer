package dev.anilbeesetti.nextplayer.settings.screens.about

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.anilbeesetti.nextplayer.core.ui.R
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var availableRelease by remember { mutableStateOf<AppRelease?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingAppDownload?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        downloadedApk?.takeIf(File::exists)?.let { apk ->
            if (AppUpdateManager.canInstallPackages(context)) {
                AppUpdateManager.install(context, apk)
            }
        }
    }

    DisposableEffect(pendingDownload?.id) {
        val pending = pendingDownload
        if (pending == null) return@DisposableEffect onDispose {}

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != pending.id) return
                val manager = receiverContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val status = manager.query(DownloadManager.Query().setFilterById(pending.id)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use DownloadManager.STATUS_FAILED
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                pendingDownload = null
                if (status != DownloadManager.STATUS_SUCCESSFUL || !pending.file.exists()) {
                    Toast.makeText(receiverContext, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    return
                }

                downloadedApk = pending.file
                if (AppUpdateManager.canInstallPackages(receiverContext)) {
                    AppUpdateManager.install(receiverContext, pending.file)
                } else {
                    Toast.makeText(receiverContext, R.string.allow_install_unknown_apps, Toast.LENGTH_LONG).show()
                    permissionLauncher.launch(AppUpdateManager.installPermissionIntent(receiverContext))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Button(
        onClick = {
            scope.launch {
                isChecking = true
                when (val result = AppUpdateManager.check(context)) {
                    UpdateCheckResult.UpToDate -> Toast.makeText(
                        context,
                        R.string.already_latest_version,
                        Toast.LENGTH_SHORT,
                    ).show()

                    is UpdateCheckResult.Available -> availableRelease = result.release
                    is UpdateCheckResult.Failed -> Toast.makeText(
                        context,
                        R.string.update_check_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                isChecking = false
            }
        },
        enabled = !isChecking && pendingDownload == null,
        colors = ButtonDefaults.buttonColors(
            contentColor = MaterialTheme.colorScheme.onSecondary,
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (isChecking || pendingDownload != null) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSecondary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = stringResource(R.string.check_for_updates))
        }
    }

    availableRelease?.let { release ->
        AlertDialog(
            onDismissRequest = { availableRelease = null },
            title = { Text(stringResource(R.string.update_available, release.versionName)) },
            text = {
                Text(
                    release.releaseNotes.ifBlank {
                        stringResource(R.string.update_available_description)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        availableRelease = null
                        runCatching { AppUpdateManager.download(context, release) }
                            .onSuccess {
                                pendingDownload = it
                                Toast.makeText(context, R.string.update_download_started, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                            }
                    },
                ) {
                    Text(stringResource(R.string.download_and_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { availableRelease = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
