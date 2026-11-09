package com.whispercppdemo.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canProcess by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
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
            progress = 0.05f
            printMessage("正在載入語音模型...\n")
            val models = application.assets.list("models/")
            if (!models.isNullOrEmpty()) {
                progress = 0.15f
                whisperContext = WhisperContext.createContextFromAsset(
                    application.assets,
                    "models/" + models[0]
                )
                progress = 1f
                printMessage("模型載入完成：${models[0]}\n請選擇「純日文」或「純英文」媒體檔開始聽譯。\n\n")
                canProcess = true
            } else {
                progress = 1f
                printMessage("錯誤：assets/models 資料夾中找不到模型檔案。\n")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Model load failed", e)
            progress = 1f
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

    fun processMedia(mediaUri: Uri, sourceLang: String) {
        if (!canProcess) return
        viewModelScope.launch(Dispatchers.IO) {
            canProcess = false
            progress = 0.05f
            dataLog = ""
            val langName = if (sourceLang == "ja") "純日文" else "純英文"
                printMessage("【1/4】準備處理「$langName」媒體檔...\n")

            try {
                // 1. 使用 Android 原生硬體解碼抽取音軌並轉為 16kHz 單聲道 FloatArray
                printMessage("【2/4】正在解碼媒體檔音訊並轉檔（16kHz）...\n")
                val audioData = extractAudioTo16kMonoFloatArray(application, mediaUri)
                progress = 0.30f
                if (audioData.isEmpty()) {
                    throw Exception("無法解碼音訊（可能檔案沒有音軌，或手機不支援此格式）")
                }

                // 2. Whisper 語音辨識
                printMessage("【3/4】Whisper 正在辨識語音（媒體檔較長時請耐心等候）...\n")
                progress = 0.40f
                val segments = whisperContext?.transcribeSegments(audioData, sourceLang) ?: emptyList()
                progress = 0.65f

                if (segments.isEmpty()) {
                    printMessage("未辨識到任何語音內容。\n")
                    canProcess = true
                    return@launch
                }
                printMessage("辨識完成，共 ${segments.size} 句！\n")

                // 3. ML Kit 離線翻譯 + 轉換為繁體中文
                printMessage("【4/4】正在檢查離線翻譯包並翻譯成繁體中文...\n")
                progress = 0.70f
                val mlSourceLang = if (sourceLang == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSourceLang)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build()
                val translator = Translation.getClient(options)

                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
                progress = 0.80f

                val srtBuilder = StringBuilder()
                for ((index, seg) in segments.withIndex()) {
                    val translatedSimp = translator.translate(seg.text).await()
                    val translatedTrad = ZhConverterUtil.toTraditional(translatedSimp)
                    progress = 0.80f + 0.15f * (index + 1) / segments.size

                    srtBuilder.append("${seg.index}\n")
                    srtBuilder.append("${seg.startTime} --> ${seg.endTime}\n")
                    srtBuilder.append("$translatedTrad\n\n")
                }
                translator.close()

                val finalSrt = srtBuilder.toString()
                lastSrtContent = finalSrt

                // 4. 儲存 .srt 到手機 Download 資料夾
                progress = 0.98f
                val savedFileName = saveSrtToDownloads(finalSrt, sourceLang)
                progress = 1f
                printMessage("\n✅ 全部完成！字幕檔已儲存至手機「下載 (Download)」資料夾：\n檔名：$savedFileName\n\n--- 字幕預覽 ---\n$finalSrt")

            } catch (e: Exception) {
                Log.e(LOG_TAG, "Process failed", e)
                printMessage("\n❌ 發生錯誤：${e.localizedMessage}\n")
            } finally {
                if (progress < 1f) progress = 1f
                canProcess = true
            }
        }
    }

    // Android 原生硬體音軌解碼與 16kHz 單聲道重採樣
    private fun extractAudioTo16kMonoFloatArray(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }
        if (audioTrackIndex == -1 || inputFormat == null) {
            extractor.release()
            return FloatArray(0)
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val rawSamples = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        while (true) {
            if (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                if (bufferInfo.size > 0) {
                    outBuffer.position(bufferInfo.offset)
                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val shortArray = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(shortArray)

                    // 混合多聲道為單聲道並轉為 [-1.0, 1.0] Float
                    var i = 0
                    while (i < shortArray.size) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            if (i + c < shortArray.size) {
                                sum += shortArray[i + c] / 32768.0f
                            }
                        }
                        rawSamples.add(sum / channelCount)
                        i += channelCount
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // 線性重採樣至 Whisper 要求的 16000Hz
        if (sampleRate == 16000) {
            return rawSamples.toFloatArray()
        }
        val ratio = sampleRate.toDouble() / 16000.0
        val newLength = (rawSamples.size / ratio).toInt()
        val resampled = FloatArray(newLength)
        for (i in 0 until newLength) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, rawSamples.size - 1)
            resampled[i] = rawSamples[srcIdx]
        }
return resampled
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
