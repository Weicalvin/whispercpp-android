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
        messageLog = viewModel.dataLog,
        onVideoSelected = { uri, lang -> viewModel.processVideo(uri, lang) },
        copyToClipboard = viewModel.copyToClipboard
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    canProcess: Boolean,
    messageLog: String,
    onVideoSelected: (Uri, String) -> Unit,
    copyToClipboard: () -> Unit,
) {
    var selectedLang by remember { mutableStateOf("ja") }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onVideoSelected(uri, selectedLang)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("離線影片聽譯繁中 (Whisper)") }
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
                        videoPickerLauncher.launch("video/*")
                    }
                ) {
                    Text("選擇純日文影片")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = canProcess,
                    onClick = {
                        selectedLang = "en"
                        videoPickerLauncher.launch("video/*")
                    }
                ) {
                    Text("選擇純英文影片")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
