package com.whispercppdemo.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.decodeWaveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canProcess by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set

    private var lastSrtContent by mutableStateOf("")
    private var whisperContext: WhisperContext? = null

    init {
        viewModelScope.launch {
            loadModel()
        }
    }

    private suspend fun loadModel() = withContext(Dispatchers.IO) {
        try {
            printMessage("正在載入語音模型...\n")
            val models = application.assets.list("models/")
            if (!models.isNullOrEmpty()) {
                whisperContext = WhisperContext.createContextFromAsset(
                    application.assets,
                    "models/" + models[0]
                )
                printMessage("模型載入完成：${models[0]}\n請選擇「純日文」或「純英文」影片開始聽譯。\n\n")
                canProcess = true
            } else {
                printMessage("錯誤：assets/models 資料夾中找不到模型檔案。\n")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Model load failed", e)
            printMessage("模型載入失敗：${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    val copyToClipboard = {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("srt_subtitles", lastSrtContent)
        clipboard.setPrimaryClip(clip)
    }

    fun processVideo(videoUri: Uri, sourceLang: String) {
        if (!canProcess) return
        viewModelScope.launch(Dispatchers.IO) {
            canProcess = false
            dataLog = ""
            val langName = if (sourceLang == "ja") "純日文" else "純英文"
            printMessage("【1/4】準備處理「$langName」影片...\n")

            var tempVideoFile: File? = null
            var tempWavFile: File? = null

            try {
                // 1. 複製選取的影片到快取目錄供 FFmpeg 讀取
                tempVideoFile = File(application.cacheDir, "input_video.mp4")
                application.contentResolver.openInputStream(videoUri)?.use { input ->
                    tempVideoFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("無法讀取影片檔案")

                // 2. FFmpeg 抽取音軌並轉為 16kHz 單聲道 WAV
                printMessage("【2/4】正在從影片抽取音軌並轉檔...\n")
                tempWavFile = File(application.cacheDir, "extracted_audio.wav")
                if (tempWavFile.exists()) tempWavFile.delete()

                val cmd = "-y -i \"${tempVideoFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${tempWavFile.absolutePath}\""
                val session = FFmpegKit.execute(cmd)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    throw Exception("FFmpeg 音訊抽取失敗")
                }

                // 3. Whisper 語音辨識
                printMessage("【3/4】Whisper 正在辨識語音（影片較長時請耐心等候）...\n")
                val audioData = decodeWaveFile(tempWavFile)
                val segments = whisperContext?.transcribeSegments(audioData, sourceLang) ?: emptyList()

                if (segments.isEmpty()) {
                    printMessage("未辨識到任何語音內容。\n")
                    canProcess = true
                    return@launch
                }
                printMessage("辨識完成，共 ${segments.size} 句！\n")

                // 4. ML Kit 離線翻譯 + 轉換為繁體中文
                printMessage("【4/4】正在檢查離線翻譯包並翻譯成繁體中文...\n")
                val mlSourceLang = if (sourceLang == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSourceLang)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build()
                val translator = Translation.getClient(options)

                // 首次使用需連網下載約 30MB 離線語言包，之後可完全斷網使用
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()

                val srtBuilder = StringBuilder()
                for (seg in segments) {
                    val translatedSimp = translator.translate(seg.text).await()
                    val translatedTrad = ChineseUtils.s2twp(translatedSimp)

                    srtBuilder.append("${seg.index}\n")
                    srtBuilder.append("${seg.startTime} --> ${seg.endTime}\n")
                    srtBuilder.append("$translatedTrad\n\n")
                }
                translator.close()

                val finalSrt = srtBuilder.toString()
                lastSrtContent = finalSrt

                // 5. 儲存 .srt 到手機 Download 資料夾
                val savedFileName = saveSrtToDownloads(finalSrt, sourceLang)
                printMessage("\n✅ 全部完成！字幕檔已儲存至手機「下載 (Download)」資料夾：\n檔名：$savedFileName\n\n--- 字幕預覽 ---\n$finalSrt")

            } catch (e: Exception) {
                Log.e(LOG_TAG, "Process failed", e)
                printMessage("\n❌ 發生錯誤：${e.localizedMessage}\n")
            } finally {
                tempVideoFile?.delete()
                tempWavFile?.delete()
                canProcess = true
            }
        }
    }

    private fun saveSrtToDownloads(content: String, lang: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Whisper_${lang}_$timestamp.srt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = application.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                application.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            file.writeText(content, Charsets.UTF_8)
        }
        return fileName
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}
