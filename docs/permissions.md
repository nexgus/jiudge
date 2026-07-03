# 權限清單

本文件記錄 app 宣告的 Android 權限及其用途. 權限宣告於 `app/src/main/AndroidManifest.xml`. 與儲存相關的取捨另見 `CLAUDE.md` 的儲存決策理由.

## 目前宣告的權限

| 權限 | 授權概略字樣 | 適用範圍 | 用途與原因 | 授權方式 |
|---|---|---|---|---|
| `INTERNET` | 無提示 | 全版本 | 下載地圖與路線規劃資料; 這是 app 唯一會用到網路的地方, 其餘功能全部離線 | 安裝時自動授予 |
| `FOREGROUND_SERVICE` | 無提示 | Android 9 (API 28+) | 首次約 400 MB 的地圖下載與軌跡錄製期間, 即使切到背景或關螢幕也能持續進行不中斷 | 安裝時自動授予 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 無提示 | Android 14 (API 34+) | 可以在螢幕關閉時持續下載地圖資料 | 安裝時自動授予 |
| `FOREGROUND_SERVICE_LOCATION` | 無提示 | Android 14 (API 34+) | 讓軌跡錄製在背景持續運作不被系統中止 | 安裝時自動授予 |
| `WAKE_LOCK` | 無提示 | 全版本 | 長時間錄製軌跡或下載時保持 CPU 運作, 避免休眠導致漏記或中斷 | 安裝時自動授予 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | "要允許 Jiudge 一律在背景執行嗎?" (系統對話框) | Android 6 (API 23+; 本 app minSdk 26 故等同全版本) | 未列入省電豁免時, 熄屏後長時間靜止 (例如中途休息) 會進入 Doze, 上一列的 CPU 鎖會被系統無視而使軌跡掉點; 豁免後 CPU 鎖在 Doze 中仍有效 | 權限本身安裝時自動授予; 實際豁免由開始錄製前的說明對話框引導使用者在系統對話框按 "允許". 拒絕或略過不影響開始錄製, 每次開始錄製會再次詢問 |
| `POST_NOTIFICATIONS` | "允許傳送通知" (系統詢問 "允許 Jiudge 傳送通知?") | Android 13 (API 33+) | 在通知列顯示地圖下載進度與軌跡錄製狀態 | 執行期請求 |
| `ACCESS_FINE_LOCATION` | "允許存取這部裝置的位置資訊?" (選 "精確") | 全版本 | 用 GPS 精確定位你的所在位置, 在地圖上顯示目前位置標記與羅盤朝向, 並作為軌跡錄製的資料來源 | 執行期請求 |
| `ACCESS_COARSE_LOCATION` | 同上位置提示 (選 "大略") | 全版本 | 概略定位你的位置, 通常與精確定位一併要求 | 執行期請求 |
| `ACCESS_BACKGROUND_LOCATION` | 位置權限改為 "一律允許" | Android 10 (API 29+) | 關螢幕或 app 切到背景時, 仍持續錄製你的軌跡 | 執行期請求 (須先取得前景定位; Android 11+ 由系統跳至位置權限設定頁選 "一律允許") |
| `MANAGE_EXTERNAL_STORAGE` | "允許存取所有檔案" (設定頁切換開關) | Android 11 (API 30+) | 把你規劃的路線與錄下的軌跡存到 "文件/Jiudge" 資料夾, 即使解除安裝或重新安裝 app 也不會遺失 | 特殊權限: 系統設定的 "所有檔案存取權" 頁, 由使用者手動開啟 (app 會先顯示用途說明對話框) |
| `WRITE_EXTERNAL_STORAGE` | "允許存取裝置上的相片, 媒體和檔案" | Android 8.0-10 (API 26-29) | 在 Android 10 以下的舊系統上, 同樣把路線與軌跡存到 "文件/Jiudge" 資料夾 (新系統改用上一項權限) | 執行期請求 |

### 相關旗標

- `android:requestLegacyExternalStorage="true"` (於 `<application>`): 並非權限, 而是讓 Android 10 (API 29) 在 scoped storage 下仍沿用傳統儲存模式, 使 `WRITE_EXTERNAL_STORAGE` 能寫入公共目錄. App 一旦 target API 30+, 此旗標在 Android 11+ 裝置上會被系統忽略, 故僅對 Android 10 有效.

## 軌跡錄製的背景運作機制

錄製軌跡要同時滿足兩件事: 錄製中螢幕保持恆亮 (除非使用者自己按電源鍵熄屏), 以及熄屏鎖定後仍持續錄製不掉點. 這靠下列五個機制疊合而成, 其中部分是權限 (詳見上表), 部分是執行期的 API 呼叫:

| 模式 (Android 正式名稱) | 常數或識別字 | 白話描述 |
|---|---|---|
| Keep screen on (window flag) | `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` | 告訴系統 "這個畫面顯示期間, 不要倒數熄屏". 僅於錄製中 (RECORDING) 設定, 停止或結束即清除恢復正常逾時; 電源鍵仍可手動熄屏. 非權限, 不需任何授權 |
| Foreground Service (location type) | 權限 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`; 啟動時帶 `FOREGROUND_SERVICE_TYPE_LOCATION` | 告訴系統 "我有一件正在進行的工作, 不要殺我", 並掛常駐通知. 少了它, app 退到背景幾分鐘就可能被回收, 錄製中斷 |
| Background location | 權限 `ACCESS_BACKGROUND_LOCATION` | 告訴系統 "螢幕關閉, app 不在前景時, 仍讓我收到 GPS 位置". Android 10 起沒有它, 背景服務收不到定位 |
| Wake lock (partial) | 權限 `WAKE_LOCK`; 鎖型別 `PowerManager.PARTIAL_WAKE_LOCK` | 持有一把 "CPU 不休眠鎖": 螢幕關了, 處理器仍醒著處理每一筆定位並寫檔. 螢幕與背光照常斷電, 只保 CPU |
| Battery optimization exemption (Doze whitelist) | 權限 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; 申請 intent `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; 查詢 `PowerManager.isIgnoringBatteryOptimizations()` | 請使用者把本 app 電池用量設為 "不受限制". 熄屏後長時間靜止, 系統進入深度省電 (Doze) 時會無視上面那把 CPU 鎖, 只有豁免的 app 例外; 行進間手機一直在動, 不會觸發 Doze, 故只影響長時間靜止的休息段 |

開始錄製時的把關順序: `POST_NOTIFICATIONS` (未授予仍可錄, 僅通知不顯示) -> `ACCESS_BACKGROUND_LOCATION` (未授予則不開錄) -> 電池最佳化豁免 (advisory: 允許, 拒絕或略過皆照常開錄, 未豁免時每次開始錄製都會再次詢問).

另注意: 此豁免只處理 AOSP 標準的 Doze. 部分廠牌 (小米, OPPO 等) 另有自家更激進的省電機制, 不在此白名單管轄範圍, 必要時須由使用者在各廠牌的設定中另外放行.
