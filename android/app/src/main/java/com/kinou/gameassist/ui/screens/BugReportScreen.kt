package com.kinou.gameassist.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kinou.gameassist.R
import com.kinou.gameassist.data.diagnostics.DiagnosticsReport
import com.kinou.gameassist.data.diagnostics.DiagnosticsReporter
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.ui.theme.DarkBackground
import com.kinou.gameassist.ui.theme.DarkCard
import com.kinou.gameassist.ui.theme.DarkCardBorder
import com.kinou.gameassist.ui.theme.NeonCyan
import com.kinou.gameassist.ui.theme.TextPrimary
import com.kinou.gameassist.ui.theme.TextSecondary
import com.kinou.gameassist.util.AppLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportScreen(
    activeProfile: GameProfile?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var userNote by remember { mutableStateOf("") }
    // Environment and logs are frozen when the screen opens: that is the state at bug time, and it
    // keeps the preview from re-querying the system on every keystroke.
    val baseReport = remember(activeProfile) {
        DiagnosticsReporter.collect(context, activeProfile, userNote = "")
    }
    val reportText = remember(baseReport, userNote) {
        baseReport.copy(userNote = userNote).format()
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = NeonCyan)
                        Text(stringResource(R.string.bug_report_title), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bug_report_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = userNote,
                onValueChange = { userNote = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.bug_report_note_label)) },
                placeholder = { Text(stringResource(R.string.bug_report_note_hint)) },
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonCyan
                )
            )

            Text(
                text = stringResource(R.string.bug_report_privacy_note),
                color = TextSecondary,
                fontSize = 11.sp
            )

            Text(
                text = stringResource(R.string.bug_report_preview_label),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SelectionContainer {
                    Text(
                        text = reportText,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { copyReport(context, reportText) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bug_report_copy), fontSize = 12.sp)
                }
                Button(
                    onClick = { shareReport(context, baseReport.copy(userNote = userNote)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DarkBackground)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bug_report_share), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun copyReport(context: Context, reportText: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("OpenMapper bug report", reportText))
    Toast.makeText(context, context.getString(R.string.bug_report_copied), Toast.LENGTH_SHORT).show()
}

private fun shareReport(context: Context, report: DiagnosticsReport) {
    try {
        val file = DiagnosticsReporter.writeToCache(context, report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OpenMapper bug report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.bug_report_share)))
    } catch (e: Exception) {
        AppLog.e("BugReport", "failed to share report", e)
        Toast.makeText(context, context.getString(R.string.bug_report_share_failed), Toast.LENGTH_SHORT).show()
    }
}
