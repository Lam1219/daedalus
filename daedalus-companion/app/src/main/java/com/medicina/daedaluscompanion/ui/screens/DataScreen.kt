package com.medicina.daedaluscompanion.ui.screens

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.auth.AuthState
import com.medicina.daedaluscompanion.data.CloudBackup
import com.medicina.daedaluscompanion.data.LocalStore
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import kotlinx.coroutines.launch

private enum class DeleteAction { FREE_UP, UPLOADED, EVERYTHING, ACCOUNT }

/** Profile → Your data: account backup, and the three ways to delete data. */
@Composable
fun DataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = (context as? Activity)?.application as? DaedalusApplication ?: return
    val store = remember { LocalStore(context) }
    val signedIn = app.auth.state.collectAsStateWithLifecycle().value is AuthState.SignedIn

    var backupOn by remember { mutableStateOf(store.cloudBackup) }
    var sessions by remember { mutableStateOf(loadSessions(context)) }
    var busy by remember { mutableStateOf(false) }
    @StringRes var message by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<DeleteAction?>(null) }

    fun run(block: suspend () -> Int) {
        busy = true; message = null
        // In CloudBackup's own scope: a deletion finishes even if the person leaves this screen.
        CloudBackup.launch {
            message = block()
            sessions = loadSessions(context)
            backupOn = store.cloudBackup
            busy = false
        }
    }

    ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
        BackHeader(stringResource(R.string.data_title), onBack)
        Text(stringResource(R.string.data_promise_short), fontSize = 14.sp, color = DesignTokens.Muted)

        // Backup
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            if (signedIn) {
                ListRow(
                    title = stringResource(R.string.data_backup_title),
                    subtitle = stringResource(if (backupOn) R.string.data_backup_on else R.string.data_backup_off),
                    icon = if (backupOn) Icons.Outlined.CloudUpload else Icons.Outlined.CloudOff,
                    trailing = {
                        SageSwitch(checked = backupOn, enabled = !busy, onCheckedChange = { on ->
                            store.cloudBackup = on; backupOn = on
                            if (on) run { if (CloudBackup.sync(app)) R.string.data_backup_done else R.string.data_backup_failed }
                        })
                    },
                )
            } else {
                ListRow(
                    title = stringResource(R.string.data_guest_title),
                    subtitle = stringResource(R.string.data_guest_sub),
                    icon = Icons.Outlined.CloudOff,
                )
            }
        }

        // Delete
        Text(stringResource(R.string.data_delete_section), style = SectionStyle, modifier = Modifier.padding(top = 4.dp))
        SageCard(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            if (signedIn) {
                val sizeLabel = formatFileSize(sessions.sumOf { it.size })
                ListRow(
                    title = stringResource(R.string.data_free_up_title),
                    subtitle = when {
                        !backupOn -> stringResource(R.string.data_free_up_needs_backup)
                        sessions.isEmpty() -> stringResource(R.string.data_free_up_empty)
                        else -> stringResource(R.string.data_free_up_sub, sessions.size, sizeLabel)
                    },
                    icon = Icons.Outlined.CleaningServices,
                    showDivider = true,
                    onClick = if (backupOn && sessions.isNotEmpty() && !busy) ({ confirm = DeleteAction.FREE_UP }) else null,
                )
                ListRow(
                    title = stringResource(R.string.data_delete_uploaded_title),
                    subtitle = stringResource(R.string.data_delete_uploaded_sub),
                    icon = Icons.Outlined.CloudOff,
                    showDivider = true,
                    onClick = if (!busy) ({ confirm = DeleteAction.UPLOADED }) else null,
                )
            }
            ListRow(
                title = stringResource(R.string.data_delete_all_title),
                subtitle = stringResource(if (signedIn) R.string.data_delete_all_sub else R.string.data_delete_all_guest_sub),
                icon = Icons.Outlined.DeleteForever,
                showDivider = signedIn,
                onClick = if (!busy) ({ confirm = DeleteAction.EVERYTHING }) else null,
            )
            if (signedIn) {
                ListRow(
                    title = stringResource(R.string.data_delete_account_title),
                    subtitle = stringResource(R.string.data_delete_account_sub),
                    icon = Icons.Outlined.PersonRemove,
                    onClick = if (!busy) ({ confirm = DeleteAction.ACCOUNT }) else null,
                )
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DesignTokens.Sage, trackColor = DesignTokens.SageTrack)
        message?.let { Text(stringResource(it), fontSize = 14.sp, color = DesignTokens.SageDeep) }
    }

    confirm?.let { action ->
        val (title, body, button) = when (action) {
            DeleteAction.FREE_UP -> Triple(R.string.data_free_up_title, R.string.data_free_up_confirm, R.string.data_free_up_button)
            DeleteAction.UPLOADED -> Triple(R.string.data_delete_uploaded_title, R.string.data_delete_uploaded_confirm, R.string.data_delete_button)
            DeleteAction.EVERYTHING -> Triple(
                R.string.data_delete_all_title,
                if (signedIn) R.string.data_delete_all_confirm else R.string.data_delete_all_guest_confirm,
                R.string.data_delete_button,
            )
            DeleteAction.ACCOUNT -> Triple(R.string.data_delete_account_title, R.string.data_delete_account_confirm, R.string.data_delete_account_button)
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = DesignTokens.Surface,
            shape = RoundedCornerShape(DesignTokens.RadiusXLarge),
            title = { Text(stringResource(title), style = SectionStyle) },
            text = { Text(stringResource(body), fontSize = 15.sp, color = DesignTokens.Ink, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    run {
                        when (action) {
                            DeleteAction.FREE_UP -> when (CloudBackup.freeUpPhoneSpace(app)) {
                                CloudBackup.FreeUpResult.DONE -> R.string.data_free_up_done
                                CloudBackup.FreeUpResult.NOT_BACKED_UP -> R.string.data_free_up_not_backed_up
                            }
                            DeleteAction.UPLOADED ->
                                if (CloudBackup.deleteUploaded(app)) R.string.data_delete_uploaded_done else R.string.data_delete_failed
                            // Signed-in accounts are signed out afterwards, which leaves this screen.
                            DeleteAction.EVERYTHING ->
                                if (CloudBackup.deleteEverything(app)) R.string.data_delete_all_done else R.string.data_delete_failed
                            // Signs out afterwards, which leaves this screen.
                            DeleteAction.ACCOUNT ->
                                if (CloudBackup.deleteAccount(app)) R.string.data_delete_all_done else R.string.data_delete_failed
                        }
                    }
                }) { Text(stringResource(button), color = DesignTokens.Alert, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel), color = DesignTokens.Muted) }
            },
        )
    }
}
