# 離線影音字幕 · Whisper.cpp Android

這個 Android App 使用 whisper.cpp 在手機本機辨識音訊，再用 Google ML Kit 離線翻譯並透過 OpenCC 轉成臺灣繁體中文 SRT。原始媒體不會上傳到雲端。

## 本地模型選擇

App 內可以選擇兩個內建模型：

- **Whisper Tiny**：速度較快、模型較小，適合一般手機與短片。
- **Whisper Base**：辨識品質較佳，但需要更多儲存空間與處理時間。

第一次使用時，ML Kit 可能需要下載對應的日文或英文離線翻譯包；下載完成後即可離線使用。

## 下載 APK

1. 開啟 GitHub Actions：<https://github.com/Weicalvin/whispercpp-android/actions>
2. 進入最新的 **Build Offline Traditional Chinese Subtitle APK** workflow。
3. 等待 `build` 成功後，在頁面底部下載 `Whisper-ZhTW-Offline-APK` artifact。
4. 解壓縮後安裝 `app-debug.apk`。Android 可能需要允許瀏覽器或檔案管理器安裝未知來源 App。

Workflow 會自動下載相容的 whisper.cpp 原始碼、`ggml-tiny.bin` 與 `ggml-base.bin`，所以模型不需要提交到 Git 儲存庫。

## 本機 Android Studio 建置

```bash
export WHISPER_CPP_DIR=/path/to/whisper.cpp
./gradlew -PWHISPER_CPP_DIR="$WHISPER_CPP_DIR" assembleDebug
```

若要本機執行，請將 `ggml-tiny.bin` 與 `ggml-base.bin` 放到 `app/src/main/assets/models/`，並使用 Android Studio 或 Gradle 建置。

## 支援格式與輸出

支援 MP4、MOV、MP3、M4A、WAV（實際格式取決於手機解碼器）。字幕會以 UTF-8 `.srt` 保存到手機的 `Download` 資料夾，並保留最近 20 筆本機歷史。
