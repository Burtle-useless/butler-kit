# 從本體同步時，這些檔案不要覆蓋

butler-kit 是從一個私有的本體 repo 同步出來的精簡版。同步功能改動時，
**下面這些檔案是 kit 專屬的**，直接從本體覆蓋會把它們變回本體的樣子。

## 外觀（kit 這邊刻意是佔位的）

本體有一套完整的視覺設計與吉祥物，那是作者的。kit 附的是白底灰字＋一顆點，
目的是「能跑但毫無個性，逼使用它的人自己設計」。覆蓋回去等於每個人做出來的
App 都長得跟作者的一樣——這正是要避免的事。

- `app/app/src/main/java/dev/butlerkit/app/ui/Theme.kt`
- `app/app/src/main/java/dev/butlerkit/app/ui/PetBot.kt`
- `app/app/src/main/java/dev/butlerkit/app/widget/WidgetUi.kt`（色票抄本）
- `app/app/src/main/res/values/colors.xml`
- `app/app/src/main/res/values-v31/themes.xml`
- `app/app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/app/src/main/res/drawable/ic_notify.xml`
- `app/app/src/main/res/values/strings.xml`（App 名稱）

各畫面裡明確指定 `FontFamily.SansSerif` 的地方也不用跟著本體改回襯線。

## 功能檔裡夾著的設計決定（這條踩過）

`ChatScreen.kt`、`KanbanScreen.kt` 這些是功能檔，照規則要同步。但它們裡面除了
邏輯之外還有文案與版面決定——空畫面上寫不寫字、頂欄顯不顯示連線狀態、按鈕上
印什麼字。**同步的是邏輯，不是這些。**

判準很簡單：一個改動如果只影響「看起來怎樣」而不影響「能做什麼」，那是本體作者
的品味，不要帶過來。

例子：頂欄的連線狀態。kit 現在是連上時不寫字，只在「連線中…」與「斷線」時才印
（連上與否，設定頁的「狀態」列另外看得到）。本體之後要是改了這一類文案或版面，
kit 維持現在的樣子，不跟著改——kit 的外觀要不要動是另一個決定，不屬於同步。

## 電腦版網頁（`server/web/`）

**整包不帶過來。** 本體有一套瀏覽器版介面（`server/web/` 底下的 HTML/CSS/JS），
kit 這邊刻意不做——它整個是作者的視覺語彙，而中性佔位版等於要重畫一次，
不是同步的範圍。手機 App 才是 kit 的主體。

伺服器端相關的路由若有改動照樣同步，只是 kit 這邊沒有前端會用到它。

## 文件

- `README.md`、`AGENTS.md`、`SYNC.md`、`docs/` — 這幾份是寫給「拿 kit 來部署的
  人與 AI」看的，本體的文件是寫給作者自己看的，兩邊讀者不同。

## 人格

- `server/personas/*.txt` — kit 只附中性範例，本體那份是作者的個性。

---

## 註解裡的原話

本體的註解常寫「使用者某月某日回報『某個畫面怎樣怎樣』」這種句子。
**那些不能照抄過來。** kit 是公開的，帶過來等於把一個人的私下對話貼到網路上，
而且外人讀起來會像這個 App 一路被嫌棄。

理由要留，原話與日期不留——註解存在的價值是「為什麼要這樣寫」，那件事不用
引述誰說過什麼就講得清楚。同理，本體的視覺語彙（報紙、朱紅、單色印刷風）
也不要帶：kit 的外觀是白底灰字的佔位版，那些描述會讓人以為是設計規範。

`_leakscan.py` 掃得到後者，前者只能自己看。

---

## kit 比本體多的處理（同步時別蓋掉）

本體只在 Windows 上跑，kit 要能部署在其他平台。Claude Code 一鍵更新這一套在 kit
多了幾處，從本體整支覆蓋回去會變回「只有 Windows 走得完、其他平台卡在重新啟動中」：

- `server/engine/sdk_update.py`：`cli_path()` 照平台找 `claude.exe`／`claude`；
  更新後重啟不了時工作標成 `manual`（`_run_guarded`）。
- `server/transport/system_api.py`：`restart_supported()`，以及 `GET /v1/system/sdk`
  回的 `auto_restart`；`_after_sdk_update()` 裡其他服務重啟失敗只記 log、不影響自己的
  重啟（`_restart_peers()` 也只在 Windows 跑）。
- App 工具頁的 Claude Code 段（`ToolsScreen.kt` 的 `SdkSection`）：`manual` 狀態、
  等重啟最多五分鐘（`RESTART_WAIT_MS`，另開一個 effect 計時）、輪詢等上一次問完才排
  下一次、說明與確認文字看 `SdkStatus.autoRestart`。

---

**其餘的（server 全部、App 的功能邏輯）照同步。** 修 bug 與新功能兩邊要一致，
不然 kit 會慢慢爛掉。
