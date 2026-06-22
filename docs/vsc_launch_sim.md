# 將 Debug App 推送到模擬器執行

把 debug 版 jiudge (原生 Android) 安裝到 Android 模擬器 (BlueStacks Air) 並執行的步驟.
個人開發環境流程; `.vscode/` 已被 `.gitignore` 忽略, 不進版控.

所有指令以 `ADB` 代表 `~/Library/Android/sdk/platform-tools/adb`, `DEV` 代表裝置 `127.0.0.1:5555`.

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=127.0.0.1:5555
```

## 1. 連線至 adb

```bash
$ADB connect $DEV
$ADB devices   # 確認 $DEV 狀態為 device
```

## 2. 推送 RudyMap 資料到裝置

App 從固定路徑 `/data/local/tmp/rudymap` 讀檔 (見 `MainActivity.DATA_DIR`).
僅首次或 BlueStacks 完整重啟後需重推.

```bash
$ADB -s $DEV shell mkdir -p /data/local/tmp/rudymap
$ADB -s $DEV push \
  ~/rudymap-data/MOI_OSM_Taiwan_TOPO_Rudy.map \
  ~/rudymap-data/MOI_OSM.xml \
  ~/rudymap-data/moiosmhs_res \
  ~/rudymap-data/hgt \
  /data/local/tmp/rudymap/
```

- `hgt/` 為 `.hgt` DEM (RudyMap `hgtmix` 解壓), 供陰影使用; 缺少時地圖照常顯示, 僅無立體陰影.

## 3. 建置 debug APK

```bash
./gradlew :app:assembleDebug
# 產物: app/build/outputs/apk/debug/app-debug.apk
```

## 4. 安裝到模擬器

```bash
$ADB -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk
```

## 5. 啟動 App

```bash
$ADB -s $DEV shell am start -n io.github.nexgus.jiudge/.MainActivity
```

## 6. 關閉 App

```bash
$ADB -s $DEV shell am force-stop io.github.nexgus.jiudge
```

- 完全移除 (連同快取與資料) 改用: `$ADB -s $DEV uninstall io.github.nexgus.jiudge`

## VSCode 一鍵 (build + install + launch)

`.vscode/tasks.json` 將步驟 3-5 串成一個工作, 以 Cmd+Shift+B 執行:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "jiudge: build + install + launch",
      "type": "shell",
      "command": "./gradlew :app:assembleDebug && $ADB -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk && $ADB -s $DEV shell am start -n io.github.nexgus.jiudge/.MainActivity",
      "options": {
        "env": {
          "ADB": "${env:HOME}/Library/Android/sdk/platform-tools/adb",
          "DEV": "127.0.0.1:5555"
        }
      },
      "group": { "kind": "build", "isDefault": true },
      "problemMatcher": []
    }
  ]
}
```

完整原生開發 (中斷點除錯) 改用 Android Studio 開啟本專案根目錄.
