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

實際發生過的一次：本體把頂欄的連線狀態文字整個拿掉（作者認為正常狀態不需要標示），
當時順手也改了 kit。這是錯的——kit 這邊維持顯示「已連線」，因為對還沒做設計決定
的人來說，那是中性預設；把它拿掉反而是一個有主張的選擇。

## 文件

- `README.md`、`AGENTS.md`、`SYNC.md`、`docs/` — 這幾份是寫給「拿 kit 來部署的
  人與 AI」看的，本體的文件是寫給作者自己看的，兩邊讀者不同。

## 人格

- `server/personas/*.txt` — kit 只附中性範例，本體那份是作者的個性。

---

**其餘的（server 全部、App 的功能邏輯）照同步。** 修 bug 與新功能兩邊要一致，
不然 kit 會慢慢爛掉。
