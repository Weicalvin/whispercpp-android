package com.whispercppdemo.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    MainScreenContent(
        canProcess = viewModel.canProcess,
        progress = viewModel.progress,
        messageLog = viewModel.dataLog,
        onMediaSelected = { uri, lang -> viewModel.processMedia(uri, lang) },
        copyToClipboard = viewModel.copyToClipboard
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    canProcess: Boolean,
    progress: Float,
    messageLog: String,
    onMediaSelected: (Uri, String) -> Unit,
    copyToClipboard: () -> Unit,
) {
    var selectedLang by remember { mutableStateOf("ja") }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onMediaSelected(uri, selectedLang)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("離線媒體聽譯繁中 (Whisper)") }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = canProcess,
                    onClick = {
                        selectedLang = "ja"
                        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*"))
                    }
                ) {
                    Text("選擇純日文媒體檔")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = canProcess,
                    onClick = {
                        selectedLang = "en"
                        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*"))
                    }
                ) {
                    Text("選擇純英文媒體檔")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "支援影片與音訊檔，例如 MP4、MOV、MP3、M4A、WAV（依手機解碼器支援情況而定）",
                style = MaterialTheme.typography.bodySmall
            )

            if (!canProcess) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "處理進度：${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = copyToClipboard
            ) {
                Text("複製字幕內容到剪貼簿")
            }

            Spacer(modifier = Modifier.height(12.dp))

            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = messageLog)
            }
        }
    }
}
