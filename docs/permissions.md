# 權限清單

本文件記錄 app 宣告的 Android 權限及其用途. 權限宣告於 `app/src/main/AndroidManifest.xml`. 與儲存相關的取捨另見 `CLAUDE.md` 的儲存決策理由.

## 目前宣告的權限

| 權限 | 授權概略字樣 | 適用範圍 | 用途與原因 | 授權方式 |
|---|---|---|---|---|
| `INTERNET` | 無提示 | 全版本 | 下載地圖與路線規劃資料; 這是 app 唯一會用到網路的地方, 其餘功能全部離線 | 安裝時自動授予 |
| `FOREGROUND_SERVICE` | 無提示 | Android 9 (API 28+) | 首次約 400 MB 的地圖下載期間, 即使切到背景或關螢幕也能持續進行不中斷 | 安裝時自動授予 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 無提示 | Android 14 (API 34+) | 可以在螢幕關閉時持續下載地圖資料 | 安裝時自動授予 |
| `POST_NOTIFICATIONS` | "允許傳送通知" (系統詢問 "允許 Jiudge 傳送通知?") | Android 13 (API 33+) | 在通知列顯示地圖下載的進度 | 執行期請求 |
| `ACCESS_FINE_LOCATION` | "允許存取這部裝置的位置資訊?" (選 "精確") | 全版本 | 用 GPS 精確定位你的所在位置, 在地圖上顯示目前位置標記與羅盤朝向 | 執行期請求 |
| `ACCESS_COARSE_LOCATION` | 同上位置提示 (選 "大略") | 全版本 | 概略定位你的位置, 通常與精確定位一併要求 | 執行期請求 |
| `MANAGE_EXTERNAL_STORAGE` | "允許存取所有檔案" (設定頁切換開關) | Android 11 (API 30+) | 把你規劃的路線存到 "文件/Jiudge" 資料夾, 即使解除安裝或重新安裝 app 也不會遺失 | 特殊權限: 系統設定的 "所有檔案存取權" 頁, 由使用者手動開啟 (app 會先顯示用途說明對話框) |
| `WRITE_EXTERNAL_STORAGE` | "允許存取裝置上的相片, 媒體和檔案" | Android 8.0-10 (API 26-29) | 在 Android 10 以下的舊系統上, 同樣把路線存到 "文件/Jiudge" 資料夾 (新系統改用上一項權限) | 執行期請求 |

### 相關旗標

- `android:requestLegacyExternalStorage="true"` (於 `<application>`): 並非權限, 而是讓 Android 10 (API 29) 在 scoped storage 下仍沿用傳統儲存模式, 使 `WRITE_EXTERNAL_STORAGE` 能寫入公共目錄. App 一旦 target API 30+, 此旗標在 Android 11+ 裝置上會被系統忽略, 故僅對 Android 10 有效.

## 尚未宣告 (待背景軌跡記錄功能)

背景軌跡記錄 (foreground-service GPS) 與 GPX I/O 尚未實作 (見 `CLAUDE.md` 開發現況). 前景定位 (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`) 已於上方宣告, 背景軌跡記錄落地時只需在此基礎上增補下列權限:

| 權限 | 授權概略字樣 | 適用範圍 | 用途與原因 | 授權方式 |
|---|---|---|---|---|
| `ACCESS_BACKGROUND_LOCATION` | 位置權限改為 "一律允許" | Android 10 (API 29+) | 關螢幕或 app 切到背景時, 仍持續記錄你的軌跡 | 執行期請求 (須先取得前景定位) |
| `FOREGROUND_SERVICE_LOCATION` | 無提示 | Android 14 (API 34+) | 讓軌跡記錄在背景持續運作不被系統中止 | 安裝時自動授予 |
| `WAKE_LOCK` | 無提示 | 全版本 | 長時間記錄軌跡時保持裝置運作, 避免休眠導致漏記 | 安裝時自動授予 |
