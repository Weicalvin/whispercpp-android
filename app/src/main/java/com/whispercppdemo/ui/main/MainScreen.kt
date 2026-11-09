package com.whispercppdemo.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    MainScreenContent(
        canProcess = viewModel.canProcess,
        progress = viewModel.progress,
        selectedModel = viewModel.selectedModel,
        modelOptions = viewModel.modelOptions,
        history = viewModel.history,
        messageLog = viewModel.dataLog,
        onModelSelected = viewModel::selectModel,
        onMediaSelected = { uri, lang -> viewModel.processMedia(uri, lang) },
        copyToClipboard = viewModel.copyToClipboard,
        clearHistory = viewModel.clearHistory,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    canProcess: Boolean,
    progress: Float,
    selectedModel: String,
    modelOptions: List<MainScreenViewModel.ModelOption>,
    history: List<HistoryItem>,
    messageLog: String,
    onModelSelected: (String) -> Unit,
    onMediaSelected: (Uri, String) -> Unit,
    copyToClipboard: () -> Unit,
    clearHistory: () -> Unit,
) {
    var selectedLang by remember { mutableStateOf("ja") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) onMediaSelected(uri, selectedLang)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("離線影音字幕 · zh-TW") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("本地 Whisper.cpp", style = MaterialTheme.typography.headlineSmall)
            Text(
                "不需要 API Key 或網路。Tiny 較快，Base 辨識品質較佳；兩個模型由 GitHub Actions 一起打包。",
                style = MaterialTheme.typography.bodySmall,
            )
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    readOnly = true,
                    value = modelOptions.firstOrNull { it.assetName == selectedModel }?.label ?: selectedModel,
                    onValueChange = {},
                    label = { Text("本地模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    modelOptions.forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onModelSelected(option.assetName)
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = canProcess,
                    onClick = {
                        selectedLang = "ja"
                        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*"))
                    },
                ) { Text("日文 → 繁中") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = canProcess,
                    onClick = {
                        selectedLang = "en"
                        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*"))
                    },
                ) { Text("英文 → 繁中") }
            }
            Text(
                "支援 MP4、MOV、MP3、M4A、WAV（依手機解碼器支援情況而定）",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!canProcess) {
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("處理進度：${(progress.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = copyToClipboard) {
                Text("複製字幕內容到剪貼簿")
            }
            if (history.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("辨識歷史", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = clearHistory) { Text("清除") }
                }
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    history.forEach { item ->
                        Text(
                            "${item.mediaName}\n${item.language} · ${item.model} · ${item.completedAt}\n輸出：${item.outputFile}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        Divider()
                    }
                }
            }
            SelectionContainer {
                Text(text = messageLog, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
