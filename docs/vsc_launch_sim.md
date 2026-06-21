# 從 VSCode 在模擬器執行 Debug App

本文記錄如何從 VSCode 把 debug 版 jiudge 部署到 Android 模擬器 (BlueStacks Air) 並執行,
含 `launch.json` 設定. 這是個人開發環境流程; `.vscode/` 已被 `.gitignore` 忽略, 不進版控.

## 前提

- BlueStacks Air 已安裝並開啟.
- 已下載並解壓 RudyMap 完整版資料 (`.map` + `MOI_OSM.xml` + `moiosmhs_res/`),
  本機路徑假設為 `~/rudymap-data`.
- Android SDK 已裝; `adb` 位於 `~/Library/Android/sdk/platform-tools/adb` (預設不在 PATH).

## 步驟一: 連上模擬器的 ADB

在 BlueStacks 內開啟 ADB (Settings -> Advanced -> Android Debug Bridge),
記下其埠號 (預設 `5555`). 每次 Mac 重開或 BlueStacks 重啟後都要重連一次:

```bash
~/Library/Android/sdk/platform-tools/adb connect 127.0.0.1:5555
```

確認 Flutter 看得到裝置:

```bash
flutter devices
# 應出現類似: SM G998B (mobile) - 127.0.0.1:5555 - android-arm64 - Android 13 (API 33)
```

## 步驟二: 把 RudyMap 資料推到裝置

App 透過 `dart:io` 直接讀檔. Android 13 scoped storage 下, 推到
`/sdcard/Android/data/<pkg>/files` 的檔案屬 shell uid, App 讀不到 (errno 13).
因此原型階段改放 `/data/local/tmp/rudymap` (world-readable, App 可直接讀).

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s 127.0.0.1:5555 shell mkdir -p /data/local/tmp/rudymap
$ADB -s 127.0.0.1:5555 push \
  ~/rudymap-data/MOI_OSM.xml \
  ~/rudymap-data/moiosmhs_res \
  ~/rudymap-data/MOI_OSM_Taiwan_TOPO_Rudy.map \
  /data/local/tmp/rudymap/
```

注意: `/data/local/tmp` 在 BlueStacks 完整重啟後可能被清空. 若執行後出現讀檔失敗,
重跑這段即可. (Phase 1 會改成 App 內下載到自有目錄, 屆時不需此步驟.)

## 步驟三: 設定 `launch.json`

關鍵陷阱: VSCode 按 F5 會跑 `flutter run`, 但不會自動帶資料路徑.
程式內 `RUDYMAP_DIR` 預設為 Mac 路徑 `/Users/scgus/rudymap-data`, 在裝置上不存在,
會導致讀檔失敗. 因此必須在 `launch.json` 以 `--dart-define` 指向裝置路徑.

`.vscode/launch.json`:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "jiudge (debug, RudyMap on device)",
      "request": "launch",
      "type": "dart",
      "program": "lib/main.dart",
      "args": ["--dart-define=RUDYMAP_DIR=/data/local/tmp/rudymap"]
    },
    {
      "name": "jiudge (profile, perf check)",
      "request": "launch",
      "type": "dart",
      "flutterMode": "profile",
      "program": "lib/main.dart",
      "args": ["--dart-define=RUDYMAP_DIR=/data/local/tmp/rudymap"]
    }
  ]
}
```

- **debug 設定**: 日常開發用, 支援 hot reload (存檔後 Cmd+\).
- **profile 設定**: 量效能體感時用 (AOT, 無 debug 額外開銷). 提醒: BlueStacks 是
  Mac 上的模擬器, 效能數字偏樂觀, 非真機代表值; 效能 budget 結論仍須在實體中階手機上量.

## 步驟四: 選裝置並執行

1. 開 Run and Debug 面板 (Cmd+Shift+D).
2. 上方下拉選 `jiudge (debug, RudyMap on device)`.
3. 右下角狀態列點裝置名稱, 選 `SM G998B (127.0.0.1:5555)`.
4. 按 F5 (或 Run -> Start Debugging). VSCode 會自動 build, 安裝, 啟動, 並接上 hot reload.

## 備註

- **debug 可正常渲染**: RudyMap 樣式在 debug (JIT, asserts on) 會觸發 rendertheme 解析器
  一個過嚴的空規則斷言; `lib/core/map_data/theme_sanitizer.dart` 只在 `kDebugMode`
  清掉會觸發斷言的規則, 讓 `flutter run` 不再崩. release/profile (AOT 無 assert) 不受影響,
  使用者拿到完整樣式.
- **不必走 launch.json 時**, 也可直接用終端機跑:
  ```bash
  flutter run --dart-define=RUDYMAP_DIR=/data/local/tmp/rudymap
  ```
