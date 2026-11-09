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
import org.json.JSONArray
import org.json.JSONObject

private const val LOG_TAG = "MainScreenViewModel"
private const val HISTORY_PREFS = "transcription_history"
private const val HISTORY_KEY = "items"
private const val MODEL_PREFS = "whisper_model"
private const val MODEL_KEY = "selected_model"

data class HistoryItem(
    val mediaName: String,
    val language: String,
    val model: String,
    val completedAt: String,
    val outputFile: String,
)

class MainScreenViewModel(private val application: Application) : ViewModel() {
    data class ModelOption(val label: String, val assetName: String)

    val modelOptions = listOf(
        ModelOption("Whisper Tiny · 速度優先", "ggml-tiny.bin"),
        ModelOption("Whisper Base · 品質優先", "ggml-base.bin"),
    )

    var selectedModel by mutableStateOf(loadSelectedModel())
        private set
    var canProcess by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var history by mutableStateOf(loadHistory())
        private set

    private var lastSrtContent by mutableStateOf("")
    private var whisperContext: WhisperContext? = null

    init {
        viewModelScope.launch { loadModel() }
    }

    fun selectModel(assetName: String) {
        if (assetName == selectedModel || modelOptions.none { it.assetName == assetName }) return
        selectedModel = assetName
        application.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MODEL_KEY, assetName)
            .apply()
        viewModelScope.launch { loadModel() }
    }

    private suspend fun setProgress(value: Float) = withContext(Dispatchers.Main) { progress = value }
    private suspend fun setCanProcess(value: Boolean) = withContext(Dispatchers.Main) { canProcess = value }
    private suspend fun clearDataLog() = withContext(Dispatchers.Main) { dataLog = "" }
    private suspend fun setLastSrtContent(value: String) = withContext(Dispatchers.Main) { lastSrtContent = value }
    private suspend fun printMessage(message: String) = withContext(Dispatchers.Main) { dataLog += message }

    private suspend fun loadModel() = withContext(Dispatchers.IO) {
        setCanProcess(false)
        try {
            setProgress(0.05f)
            printMessage("正在載入本地模型：$selectedModel\n")
            whisperContext?.release()
            whisperContext = null
            val assets = application.assets.list("models/")?.toSet().orEmpty()
            if (selectedModel !in assets) {
                setProgress(1f)
                printMessage("找不到 $selectedModel。請使用 GitHub Actions 產出的 APK，或將模型放入 app/src/main/assets/models。\n")
                return@withContext
            }
            setProgress(0.2f)
            whisperContext = WhisperContext.createContextFromAsset(application.assets, "models/$selectedModel")
            setProgress(1f)
            printMessage("模型載入完成：$selectedModel\n請選擇來源語言並挑選媒體檔。\n\n")
            setCanProcess(true)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Model load failed", error)
            setProgress(1f)
            printMessage("模型載入失敗：${error.localizedMessage}\n")
        }
    }

    val copyToClipboard = {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("srt_subtitles", lastSrtContent))
    }

    val clearHistory = {
        history = emptyList()
        application.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(HISTORY_KEY)
            .apply()
    }

    fun processMedia(mediaUri: Uri, sourceLang: String) {
        if (!canProcess) return
        val modelAtStart = selectedModel
        viewModelScope.launch(Dispatchers.IO) {
            setCanProcess(false)
            setProgress(0.05f)
            clearDataLog()
            val languageName = if (sourceLang == "ja") "日文" else "英文"
            printMessage("【1/4】使用 $modelAtStart 準備處理「$languageName」媒體檔…\n")
            try {
                printMessage("【2/4】正在解碼並重採樣為 16kHz 單聲道…\n")
                val audioData = extractAudioTo16kMonoFloatArray(application, mediaUri)
                setProgress(0.3f)
                if (audioData.isEmpty()) throw Exception("無法解碼音訊，可能沒有音軌或手機不支援此格式")

                printMessage("【3/4】Whisper $modelAtStart 正在辨識語音…\n")
                setProgress(0.4f)
                val segments = whisperContext?.transcribeSegments(audioData, sourceLang).orEmpty()
                setProgress(0.65f)
                if (segments.isEmpty()) {
                    printMessage("未辨識到語音內容。\n")
                    return@launch
                }
                printMessage("辨識完成，共 ${segments.size} 段；正在轉換為臺灣繁體中文…\n")

                val sourceLanguage = if (sourceLang == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(TranslateLanguage.CHINESE)
                        .build(),
                )
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                setProgress(0.75f)
                val srtBuilder = StringBuilder()
                for ((index, segment) in segments.withIndex()) {
                    val simplified = translator.translate(segment.text).await()
                    val traditional = ZhConverterUtil.toTraditional(simplified)
                    srtBuilder.append("${index + 1}\n${segment.startTime} --> ${segment.endTime}\n$traditional\n\n")
                    setProgress(0.75f + 0.18f * (index + 1) / segments.size)
                }
                translator.close()

                val finalSrt = srtBuilder.toString()
                setLastSrtContent(finalSrt)
                val outputFile = saveSrtToDownloads(finalSrt, sourceLang, modelAtStart)
                addHistory(mediaUri, sourceLang, modelAtStart, outputFile)
                setProgress(1f)
                printMessage("\n✅ 完成！已將 UTF-8 SRT 保存至 Download：$outputFile\n\n--- 字幕預覽 ---\n$finalSrt")
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Process failed", error)
                printMessage("\n❌ 發生錯誤：${error.localizedMessage}\n")
            } finally {
                setProgress(1f)
                setCanProcess(true)
            }
        }
    }

    private fun loadSelectedModel(): String {
        val saved = application.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE).getString(MODEL_KEY, null)
        return saved ?: "ggml-tiny.bin"
    }

    private fun loadHistory(): List<HistoryItem> {
        val raw = application.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).getString(HISTORY_KEY, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        HistoryItem(
                            mediaName = item.optString("mediaName"),
                            language = item.optString("language"),
                            model = item.optString("model", "Whisper"),
                            completedAt = item.optString("completedAt"),
                            outputFile = item.optString("outputFile"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun addHistory(uri: Uri, sourceLang: String, model: String, outputFile: String) {
        val item = HistoryItem(
            mediaName = getMediaDisplayName(uri),
            language = if (sourceLang == "ja") "日文" else "英文",
            model = model.removePrefix("ggml-").removeSuffix(".bin").replaceFirstChar { it.uppercase() },
            completedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            outputFile = outputFile,
        )
        val updated = withContext(Dispatchers.Main) { (listOf(item) + history).take(20).also { history = it } }
        val json = JSONArray()
        updated.forEach { entry ->
            json.put(JSONObject().apply {
                put("mediaName", entry.mediaName)
                put("language", entry.language)
                put("model", entry.model)
                put("completedAt", entry.completedAt)
                put("outputFile", entry.outputFile)
            })
        }
        application.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).edit().putString(HISTORY_KEY, json.toString()).apply()
    }

    private fun getMediaDisplayName(uri: Uri): String {
        application.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "未命名媒體檔"
    }

    private fun extractAudioTo16kMonoFloatArray(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                audioTrackIndex = index
                inputFormat = format
                break
            }
        }
        if (audioTrackIndex == -1 || inputFormat == null) {
            extractor.release()
            return FloatArray(0)
        }
        extractor.selectTrack(audioTrackIndex)
        val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        val rawSamples = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        try {
            while (true) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)!!
                    if (bufferInfo.size > 0) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val values = ShortArray(shorts.remaining())
                        shorts.get(values)
                        var offset = 0
                        while (offset < values.size) {
                            var sum = 0f
                            for (channel in 0 until channelCount) {
                                if (offset + channel < values.size) sum += values[offset + channel] / 32768f
                            }
                            rawSamples.add(sum / channelCount)
                            offset += channelCount
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        if (sampleRate == 16_000) return rawSamples.toFloatArray()
        if (rawSamples.isEmpty()) return FloatArray(0)
        val ratio = sampleRate.toDouble() / 16_000.0
        val resampled = FloatArray((rawSamples.size / ratio).toInt())
        for (index in resampled.indices) {
            resampled[index] = rawSamples[(index * ratio).toInt().coerceIn(0, rawSamples.lastIndex)]
        }
        return resampled
    }

    private fun saveSrtToDownloads(content: String, lang: String, model: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val modelName = model.removePrefix("ggml-").removeSuffix(".bin")
        val fileName = "Whisper_${modelName}_${lang}_$timestamp.srt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            application.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                application.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, fileName).writeText(content, Charsets.UTF_8)
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
