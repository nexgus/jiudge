# Hiking Map App - Project Memory

台灣專用, 純離線的登山地圖 App, 僅支援 Android, 為單人開發的個人專案.

## Communication

- 使用者主要以**繁體中文 (台灣)**溝通, 閒聊時請以繁體中文回覆.
- 程式碼, 註解, commit, 分支名稱與 PR 標題一律使用**英文**.
- 變數命名遵循英文慣例, 切勿以羅馬拼音表示中文.

## What This Project Is

一款自製的戶外登山地圖 App, 針對既有台灣登山 App 的兩大痛點而設計: **UI / 操作體驗**與**路徑規劃 / 軌跡追蹤的品質**.

這是個人專案. **沒有後端**, 沒有使用者帳號, 沒有遙測, 沒有分析. 所有功能皆在裝置端運作. 發佈方式為 **APK side-load**. Play Store **並非**近期目標: 路徑儲存依賴 `MANAGE_EXTERNAL_STORAGE` (All files access), 而 Google Play 僅開放此權限給檔案管理類 App - 要上架 Play 必須先重構路徑儲存 (見下方儲存決策). **僅限 Android** - iOS 不在範圍內.

地圖資料**非本專案產製**. 本 App 直接取用 RudyMap (MOI.OSM Taiwan TOPO) 的資料: mapsforge `.map` 底圖, 其內附的 render theme, 以及 `.hgt` DEM. 這些檔案由 App 內向 RudyMap 的公開 mirror 下載, 僅供個人使用 - 絕不轉存或再散佈.

## Architecture

以下皆為深思熟慮後的決策 - 請視為預設, 不要隨意更動. 它們**並非**不可變更: 發現真正的問題或明顯更好的做法, 是改變的理由, 而非該壓抑的事. 要更動時, 先提出, 討論取捨, 再修改 - 並同步更新本表與下方的決策註記, 讓紀錄保持誠實.

| Layer | Choice |
|---|---|
| App framework | Native Android (Kotlin), Jetpack Compose UI, 僅限 Android |
| Map rendering | `org.mapsforge:mapsforge-map-android` (標準 Canvas renderer; in-process JVM) |
| Offline map format | 來自 RudyMap 的 mapsforge `.map` (原樣取用, 非本專案產製) |
| Map style | RudyMap 內附的 render theme, 原樣使用 (不做自訂製圖) |
| Routing engine | BRouter (`org.btools:brouter-core`, Java), in-process 呼叫 - 不需 platform channel |
| Routing data | BRouter `.rd5` segment (5x5 度 tile) + `.brf` profile, OSM 衍生 - 與 render 用的 `.map` 分離, 後者不含路網拓樸 |
| DEM / hillshade | RudyMap `.hgt` DEM (`hgtmix`); mapsforge 原生 hillshading + 裝置端高程查詢 |
| Local storage | 每條規劃路徑 / 軌跡一個 JSON/GPX 檔. **使用者路徑放在固定的公開資料夾** `Documents/Jiudge/` (`plans/`, `tracks/`), 以 `java.io.File` 寫入, 受 `MANAGE_EXTERNAL_STORAGE` (All files access) 權限把關, 因此能在解除安裝後留存, 重新安裝並重新授權後 App 可再次讀取. 可下載的 `.map`/`.rd5`/DEM 放在 `getExternalFilesDir` 之下 (`core/storage/AppPaths`). 唯有在需要查詢大量紀錄時才導入 Room (SQLite) |
| GPS background | Android Foreground Service + Wake Lock + 常駐通知 |
| Backend | 無. 地圖資料由 App 內直接向 RudyMap 公開 mirror 取得 (static HTTPS) |

若提議的函式庫或做法與本表牴觸, 請先提出再進行 - 不是因為本表神聖不可侵犯, 而是要讓變更成為留有紀錄的決策, 而非無聲的漂移.

**為何選 native 而非 Flutter (decided 2026-06-22):** 原本的 Flutter 規劃仰賴 `mapsforge_flutter` (純 Dart port). 它無法繪製 RudyMap theme 所需的方向性 hillshade (該 theme 的 `<hillshading>` painter 在 4.0.0 中被 stub 掉; 任何 DEM 起伏都得自己做, 且解析度低又昂貴), 而且其 label 去重忽略了 theme 的 `priority` 權重. 既然 hillshade 是硬需求, 且兩個核心引擎 (mapsforge rendering, BRouter routing) 都是 JVM 函式庫, native Android 能讓兩者都以 in-process, 全保真度執行 - 與 RudyMap 參考 App 一致 - 且無 platform-channel 的 marshalling 開銷.

**為何選 BRouter 而非 GraphHopper (decided 2026-06-22):** `.map` 底圖不含路網拓樸 (依格式作者的設計, mapsforge 純粹用於 rendering), 因此無論用哪個引擎, 路徑規劃都需要另一套 OSM 衍生資料 - 從 `.map` 推導 graph 既有損失又在拓樸上不可靠 (tile 裁切的幾何, 量化過的座標, 遺失的 shared-node 連通性, 無法分辨路口與跨越的天橋). 參考的技術棧 (OruxMaps/RudyMap) 同樣以獨立引擎進行路徑規劃, 而非用 render tile. BRouter 比 GraphHopper 更適合本 App: 其 `.rd5` segment 資料每區極小 (全台約 1-2 個 5x5 度 tile, 相對於完整的 `pbf` graph build), 其核心 (`brouter-core`) 可 in-process 執行, 且其 `.brf` profile 系統專為步行 / 登山路徑加權而設計. 與原本的 GraphHopper 規劃同樣具備離線, 無後端, JVM-in-process 的特性.

**為何路徑採 MANAGE_EXTERNAL_STORAGE 而非 SAF (decided 2026-06-24):** 路徑必須在解除安裝後留存, 重新安裝後可再讀取, 並放在固定, 可預期的資料夾, 且每次使用都不需挑選器. SAF (`OPEN_DOCUMENT_TREE`) 無法自動建立固定資料夾 - 它受同意把關, 且 Android 11+ 全面禁止整批授權標準共享目錄 (Documents/Download/root), 迫使使用者在每次全新安裝時走一段令人困惑的 "建立新資料夾" 流程. MediaStore 也幫不上忙: 依 Google 自己的指引它僅限媒體, 而重新安裝的 App 會失去對自身非媒體 (`.json`) 檔案的存取, 因為那需要如今已被閹割的 `READ_EXTERNAL_STORAGE`. `MANAGE_EXTERNAL_STORAGE` 是唯一能同時做到固定路徑 + 免挑選器 + 重裝可恢復的 API. 其代價是失去 Play Store 上架資格 (見 Distribution), 我們接受, 因為這是個人, side-load 的 App. 在前往設定頁之前, App 會先顯示一個說明該權限用途的 rationale 對話框. 若 Play Store 日後成為目標, 路徑儲存必須改回 SAF (每次安裝挑選資料夾) 或 app-specific storage (無法在解除安裝後留存).

## Hard Constraints

- **完全離線.** 除了 "檢查更新" 與 "在 Google Maps 開啟" 之外, 每項功能都必須在無網路下運作. 若某個實作在使用時需要連線, 請在動手寫程式前先提出.
- **長時間背景任務必須能撐過螢幕關閉與 Doze.** Foreground Service 只保證行程不被 OS 殺掉, 不保證 CPU 不被 suspend, 亦不保證 WiFi 不掉入 power-save mode. 凡需要在螢幕關閉時持續運作的背景任務 (背景軌跡錄製需 GPS 持續更新並寫檔, 地圖資料下載等) 一律 Foreground Service + `PARTIAL_WAKE_LOCK`; 任務涉及網路時額外持有 `WifiManager.WifiLock(WIFI_MODE_FULL_HIGH_PERF)`. 兩種鎖皆於 `finally` / `onDestroy` 釋放, 漏放會無聲耗電. 定位絕不可只用 `startService`.
- **僅限 Android.** 不做 iOS. 整個 App 皆為 native Kotlin/JVM; 沒有 platform channel, 也沒有跨平台抽象層. 不要加入唯一理由是 "方便日後接上 iOS" 的抽象.
- **原樣取用 RudyMap 資料.** 不要自建 tile/contour/hillshade pipeline (不用 Planetiler, 不用 GDAL 預烘焙, 不用 PMTiles). 不要自製地圖 cartography; 使用 RudyMap 內附的 theme. 若某功能需要 RudyMap `.map` 不含的地圖內容, 請在動手寫程式前先提出. (Routing 是已知例外: `.map` 不含路網拓樸, 因此路徑規劃使用自 brouter.de 下載的預建 BRouter `.rd5` segment - OSM 衍生, 而非自製 cartography pipeline.)
- **無後端.** 不要提議需要伺服器狀態, 帳號或同步的功能. 唯一的網路用途是 App 內向 RudyMap 公開 mirror 下載資料; App 不託管也不再散佈任何東西.

## Performance Budgets

- 冷啟動: < 3 s
- 導覽後第一張地圖 frame: < 1 s
- 連續錄製軌跡 8 小時: 於現代中階 Android 上 < 30% 電量消耗
- 安裝後 App 大小: < 100 MB (RudyMap 資料於首次執行時由 App 內下載, 不隨包附帶; 光底圖壓縮後就約 298 MB)

## v1 Scope Discipline

**v1 範圍內**:

- 離線地圖瀏覽 (mapsforge 標準渲染, 連續捏放與旋轉)
- 自動路線規劃 (起終點 + 中途 waypoint, snap 到 OSM 山徑)
- 路線量測 (總長度, 總爬升 / 下降, 海拔剖面圖)
- GPS 軌跡錄製 (背景錄製, 螢幕關閉持續記錄)
- 軌跡即時統計 (已走距離, 總爬升, 時間, 平均速度)
- 即時海拔剖面 (錄製中顯示已走剖面)
- GPX / KML 匯入匯出 (tracks + routes + waypoints, 相容主流登山 App)
- 圖資下載與更新 (App 內直接從 RudyMap 鏡像下載 `.map` + DEM + 樣式; "檢查更新" 比對 HTTP `Last-Modified`; 保留 "手動匯入本地檔" 備援)
- Google Maps 跳轉 ("在 Google Maps 開啟此位置")

**明確排除於 v1 之外** (不要實作, 即使某次重構 "自然而然就能順帶做到"):

- 多使用者, 帳號, 雲端同步
- 含過夜停點與逐日行程的多日路徑規劃
- 錄製途中的偏離路線警示
- 衛星 / 空拍影像疊圖
- 使用者自註的 POI (水源, 營地等)
- 林務局通訊點疊圖

若某項請求自然引向以上之一, 請將其列為 v2 候選並**停下**; 未經明確同意不要實作.

## Development Status

此處工作為**功能驅動, 而非嚴格的階段線性** - 功能在有用時才落地, 不照固定順序. 早期曾有 Phase 0-3 的階段計畫, 但本專案刻意未按其順序進行, 因此**不要**以 "那屬於後面的階段" 來阻擋或設限工作. 舊的 "絕不跳階段" 規則已廢止.

**目前已建置:**
- 含 hillshade 的離線地圖渲染 (`feature/map`)
- App 內下載 map / DEM / BRouter 資料 (`feature/mapdata`, `core/mapdata`)
- 經 BRouter 的路徑規劃 - 航點, 儲存 / 載入 (`feature/planning`, `core/routing`, `data/route`)
- 在 `.hgt` DEM 上的裝置端高程查詢, 於規劃時驅動依坡度上色的路徑線 + 公里標記
  (`core/elevation/DemElevation`, `feature/planning/PlannedRouteLayer`)
- 地圖符號辨識 ("?") (`feature/identify`)
- 主選單 / 關於 (`feature/about`)
- 現在位置標記 + 重新置中按鈕 - 前景 GPS + 羅盤朝向錐, OruxMaps 風格
  (`feature/map/CurrentLocationLayer`, `core/location`). 僅限前景 (地圖可見時訂閱, pause 時釋放);
  這**不是**下方的背景軌跡錄製
- 山頭位置索引 (`core/index`): 將底圖的 `man_made=summit_board` POI 一次掃描成一個放在 `.map`
  旁的小 TSV, 缺漏 / 過期時重建 (以底圖大小 + mtime 標記). 每次進入地圖都會檢查;
  以不阻斷的建置 banner 顯示進度
- 山名搜尋 (`feature/search`): 左側控制列的 "🔍" 開啟一個依子字串即時過濾索引的對話框;
  點選命中項會將地圖置中於該山頭 (若縮放太遠則拉近)

**尚未建置 (已知待辦):**
- 背景軌跡錄製 (foreground-service GPS) + GPX 匯入 / 匯出 - 這是原本的
  "Phase 1 核心", 仍未完成
- 規劃時的高程剖面
- 更新檢查機制

仍然適用: 做被要求的事, 不要無聲擴張範圍 (見 Working With Me), 並在未獲明確同意前, 將下方 v1 排除清單視為禁區.

## Build, Lint & Install Commands

repo 根目錄有兩支輔助腳本封裝了常見流程 (兩者都會先 `cd` 到腳本所在目錄, 因此在任何位置皆可執行):

- `./build.sh` - debug APK. `--release`/`-r` 產出 release APK, `--clean`/`-c` 先清除再建置. 內部執行 `./gradlew ktlintCheck assembleDebug` (或 `assembleRelease`). release build 還會在 APK 旁放一份自我描述的複本, 命名為 `jiudge-<version>-<hash>[-dirty].apk`.
- `./install.sh` - 以 `adb install -r` 安裝已建置的 debug APK (`--release`/`-r` 為 release). **不會**先建置. 連接多台裝置時會列出並提示選擇其一.

底層 Gradle task (不使用腳本時可直接呼叫):

- `./gradlew assembleDebug` / `assembleRelease` - APK 位於 `app/build/outputs/apk/<variant>/`.
- `./gradlew ktlintCheck` - Kotlin style 檢查; 已 wire 進 `check`. `./gradlew ktlintFormat` 自動修正. 兩者皆透過 `JavaExec` task 執行 **ktlint CLI** (非 ktlint-gradle plugin), 因此唯一的 style gate 就是 ktlint - 沒有另外的 cartography/lint 設定要滿足.
- `./gradlew test` - JUnit 單元測試. 單一測試: `./gradlew test --tests "io.github.nexgus.jiudge.SomeTest"` (或 `"...SomeTest.someMethod"`).

值得知道的建置事實:

- `minSdk 26`, `targetSdk`/`compileSdk 35`, JDK/JVM target **17**.
- BuildConfig 會在 configuration 階段烙入 git short hash 與 dirty flag (顯示於 App 內的 "關於" 對話框). 若想要乾淨的版本標籤, **release build 前請先 commit**, 因為未提交的工作樹會被標上 `-dirty`.
- BRouter (`com.github.abrensch`) 經 **JitPack** 解析, 並在 `settings.gradle.kts` 中限定於該 group (它不在 Maven Central 上). 首次建置需要網路來取得它.

## Development Conventions

### Code style
- Kotlin: ktlint 預設規則.
- 不可有未附追蹤參照的 `// TODO` (GitHub issue 編號, 或如 `// TODO(spec §4.2)` 的 spec 章節).

### Directory layout (native Android)
```
app/src/main/kotlin/io/github/nexgus/jiudge/
  feature/        # feature modules: map (+ current-location overlay), planning, identify, mapdata, about, search (peak-name lookup) (planned: recording, gpx, settings)
  core/           # shared infra: mapdata, routing, storage, location (foreground GPS + compass), elevation (.hgt DEM lookup), index (summit-position index for search) (planned: background recording service, networking)
  data/           # repositories, models, data sources (currently: route)
  ui/             # Compose components, theming, design tokens (planned)
docs/             # spec, design notes, architecture decisions
```

### Testing
- 純 Kotlin 邏輯使用單元測試 (JUnit); `core/` 與 `data/` 目標涵蓋率 > 70%
- 非簡單的 Compose 畫面使用 instrumented/UI 測試
- 整合測試保留給關鍵使用者流程 (錄製, 規劃, GPX round-trip)

### Git
- 分支: `feat/<short-desc>`, `fix/<short-desc>`, `chore/<short-desc>`
- commit 採 Conventional Commits 格式
- commit 前一律執行 `./gradlew ktlintFormat ktlintCheck` (或 `./build.sh`)
- 不要 commit build artifact, 產生的檔案, 或大型二進位資產

## Working With Me

- **新增相依套件前先詢問.** 對單人開發者而言, 每個新的 Maven/Gradle 相依都是長期維護的承諾. 請說明理由.
- **宣稱完成前先驗證.** 功能要在真實 Android 裝置上跑起來才算完成, 能編譯不算.
- **浮現未決問題.** 遇到模稜兩可不要無聲地自行決定答案. 見下方 §"Open Questions".
- **不要無聲擴張範圍.** 只做滿足請求所需的最小改動.

## Open Questions (Not Yet Decided)

不要擅自假設答案:

1. v1 是否納入林務局通訊點 / 山屋疊圖
2. 自動規劃出的路徑事後是否支援手動拖曳節點調整
3. ~~RudyMap 的 `.map` 是否帶有 `name:en` tag~~ - **已解決**: 它同時帶 zh + en, 因此 zh/en 標籤切換可行
4. 拖曳航點時是否即時顯示累計爬升

## Key External References

- RudyMap (MOI.OSM Taiwan TOPO) 下載: https://rudymap.tw/ (mirror: https://moi.kcwu.csie.org/ , https://map.happyman.idv.tw/rudy/)
  - 底圖: `MOI_OSM_Taiwan_TOPO_Rudy.map.zip` (~298 MB; Lite ~168 MB), 位於各 mirror 的 `/v1/` 路徑下
  - DEM: `hgtmix.zip` (~46 MB, 台灣 30 m + 離島 90 m) 或 `hgt90.zip` (~8 MB) - `.hgt` 格式
  - Theme: `MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip` (light + dark 兩種)
  - 靜態 HTTP GET, 無 manifest/SHA256; 版本日期 (`vYYYY.MM.DD`) 標在頁面上, 而非檔名中 - 透過 HTTP `Last-Modified`/`ETag` 偵測新版. 每週發佈 (週四).
- mapsforge 格式, theme 與 Android 函式庫 (`mapsforge-map-android`, Maven Central 最新 0.25.0): https://github.com/mapsforge/mapsforge
- BRouter (離線路徑規劃引擎, 經 `org.btools:brouter-core` in-process 嵌入): https://brouter.de/ , 原始碼 https://github.com/abrensch/brouter
  - Routing data: 5x5 度 tile 上的 `.rd5` segment 檔 (台灣 = `E120_N20`, `E120_N25`), 來自 https://brouter.de/brouter/segments4/ ; OSM 衍生, 與 render 用的 `.map` 分離. profile (`.brf`) 調校步行 / 登山加權; in-process 用法沿用 `brouter-routing-app` 的 `BRouterWorker`.
- OSM 台灣 extract (僅在自建 BRouter segment 而非使用預建 `.rd5` 時需要): https://download.geofabrik.de/asia/taiwan.html
