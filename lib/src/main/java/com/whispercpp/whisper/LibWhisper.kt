package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

data class SubtitleSegment(
    val index: Int,
    val startTime: String,
    val endTime: String,
    val text: String
)

class WhisperContext private constructor(private var ptr: Long) {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun transcribeSegments(data: FloatArray, language: String): List<SubtitleSegment> = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads, language: $language")
        WhisperLib.fullTranscribe(ptr, numThreads, data, language)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        val segments = mutableListOf<SubtitleSegment>()
        for (i in 0 until textCount) {
            val t0 = toTimestamp(WhisperLib.getTextSegmentT0(ptr, i), comma = true)
            val t1 = toTimestamp(WhisperLib.getTextSegmentT1(ptr, i), comma = true)
            val segmentText = WhisperLib.getTextSegment(ptr, i).trim()
            if (segmentText.isNotEmpty()) {
                segments.add(SubtitleSegment(i + 1, t0, t1, segmentText))
            }
        }
        return@withContext segments
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    if (cpuInfo.contains("vfpv4")) loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    if (cpuInfo.contains("fphp")) loadV8fp16 = true
                }
            }

            if (loadVfpv4) {
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                System.loadLibrary("whisper")
            }
        }

        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
    }
}

private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS[0].equals("arm64-v8a")

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}
