# butler-kit

從手機驅動你自己電腦上的 Claude Code。一個 Android App 加一支本機服務，
連線走你自己的網路，中間沒有第三方。

不是聊天機器人的殼。你在手機上打一句「電腦好卡」，它會去看記憶體與行程佔用，
然後回報數字並問你要關哪個——CC 在你電腦上有的能力，它全都有。

## 有什麼

- **對話** — SSE 串流，逐字顯示思考與回覆；工具軌跡收在可展開的區塊裡
- **多對話** — 助理一條、工作區多條，各自獨立的 CC session，接得回歷史
- **選項按鈕** — 助理要你做決定時，手機上跳出按鈕直接點
- **行事曆／鬧鐘／記帳／課表** — 你隨口說「明天十點看牙醫」它就開行程；
  鬧鐘會在手機上真的響
- **傳檔雙向** — 電腦端做出來的圖直接推到手機；手機上的截圖丟給它看
- **語音** — 語音輸入、朗讀、面對面即時翻譯（離線可用）
- **看板** — 工作看板，助理自己開卡換欄；手機上一屏一欄、用明確按鈕換欄
- **位置** — 手機背景定期回報位置，助理要用時直接讀快取；只留最新一筆，不存軌跡
- **服務控制台** — 在手機上看服務狀態（跑多久、哪一版），一鍵重啟
- **用量** — token 消耗與訂閱方案剩餘額度
- **人格** — 純文字檔，改語氣不必碰程式碼

## 開始

整包丟給你自己的 Claude Code，叫它讀 [`AGENTS.md`](AGENTS.md)——那份是寫給它看的，
從裝相依到編 APK 到配對都在裡面，連踩過的坑一起。

要自己動手的話：[`docs/manual-setup.md`](docs/manual-setup.md)。

## ⚠️ 先知道你在裝什麼

**這東西給手機端一台電腦的完整控制權。**

引擎跑在 `bypassPermissions` 下——刪檔、跑 PowerShell、讀任何檔案都不會問你。
這是刻意的：你人在外面，不可能一直點確認。代價是 **device token 外流等於電腦被接管**。

服務永遠不綁 `0.0.0.0`，拿不到 tailnet 位址就拒絕啟動，這條沒有開關。

## 換一套皮

整套視覺集中在一個檔案：`app/app/src/main/java/dev/butlerkit/app/ui/Theme.kt`——
色票、圓角、字級全在裡面，改它全 App 跟著變。預設外觀是報紙風
（紙白、墨黑、襯線字）。

想換風格的話，建議裝 `ui-ux-pro-max` 這個 Claude Code skill
（開源，內含 49 種通用 UI 風格與色彩字型資料庫）。
工作流程：請 Claude 用該 skill 產幾個方向的預覽 → 挑一個 → 只改 Theme.kt 套用。

一句提醒：中文襯線字的取得方式見 `ui/SerifProbe.kt` 的註解——
`FontFamily.Serif` 對中文是無效的，要直接載系統的 NotoSerifCJK。

## 需要

- Windows / macOS / Linux 上的 Python 3.11+，以及已登入的 Claude Code CLI
- Android 8.0 以上的手機
- JDK 17+ 與 Android SDK（編 APK 用）
- Tailscale（預設的連線方式，其他選法見 [`docs/transport.md`](docs/transport.md)）

## 文件

| | |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 給 AI 助理的部署指南 |
| [`docs/transport.md`](docs/transport.md) | 五種連線方式的優缺與選法 |
| [`docs/persona.md`](docs/persona.md) | 怎麼寫人格，以及會把它寫壞的幾種方式 |
| [`docs/manual-setup.md`](docs/manual-setup.md) | 純人工部署 |

## 授權

MIT。

`app/app/src/main/assets/opencc/` 底下的簡繁字典來自
[OpenCC](https://github.com/BYVoid/OpenCC)，以 Apache License 2.0 授權，
未經修改。歸屬說明見 [`NOTICE`](NOTICE)，授權全文見
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。
