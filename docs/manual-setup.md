# 自己動手部署

不想把整包丟給 AI 的話走這條。步驟跟 [`AGENTS.md`](../AGENTS.md) 一樣，
只是講給人聽。

## 0. 先確認

- Python 3.11+
- Claude Code CLI 裝好且登入過（終端機打 `claude` 有反應）
- JDK 17 或 21、Android SDK
- 專案放在**沒有中文的路徑**下（Android Gradle Plugin 會拒編非 ASCII 路徑）

## 1. server

```bash
cd server
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

第一次啟動印出來的 device token **只印這一次**，記下來。忘了就刪
`server/data/devices.json` 重啟。

沒裝 Tailscale 的話這裡會拒絕啟動（那是刻意的）。先跳到第 2 步，
或設 `BUTLER_DEV=1` 綁 loopback 確認服務本身沒問題——但這樣手機連不到。

## 2. 連線

預設走 Tailscale。兩端裝好、登入同一帳號、電腦端開 MagicDNS，
記下主機名（像 `desktop-abc.tailXXXX.ts.net`）。

不想用 Tailscale 就先讀 [`docs/transport.md`](transport.md)，
那份講了為什麼這個選擇綁在編譯期、以及其他四種做法各自的代價。

## 3. 編 APK

```bash
cd app
echo sdk.dir=C:\\path\\to\\Android\\Sdk > local.properties
gradlew.bat assembleDebug
```

產物在 `app/app/build/outputs/apk/debug/app-debug.apk`。傳到手機安裝，
系統會問要不要允許安裝未知來源的應用程式。

首次編譯要下載相依，幾分鐘跑不掉。

## 4. 配對

開 App，設定頁填兩格：

- host：`<主機名>.tailXXXX.ts.net:47362`
- token：第 1 步印出來那串

連上之後左上角會顯示連線狀態。

## 5. 讓它開機自啟（可選）

Windows 用工作排程器，動作設成 `pythonw.exe main.py`，
觸發程序選「登入時」。

⚠️ 不要用 `powershell.exe` 或 `cmd.exe` 當動作——以 Interactive 身分執行時
一定會閃一個黑窗，`Hidden` 選項擋不住。要無視窗就包一層 `.vbs` 用 `wscript` 跑。

server 啟動時會等 Tailscale 就緒（最多 90 秒）再綁位址，
所以開機順序不必自己處理。

## 常見狀況

**手機顯示連不上** — 依序確認：server 有在跑、兩端 Tailscale 都連著、
host 打對了（含埠號）、token 沒打錯。token 錯的症狀跟連不上一模一樣，
這是最容易卡住的地方。

**回話卡 60 秒然後報 timeout** — system prompt 裡有換行符。
見 [`docs/persona.md`](persona.md) 第一條。

**通知不跳** — 系統設定裡開通知權限，並把這支 App 的電池最佳化關掉。

**鬧鐘不響** — Android 12+ 需要「鬧鐘和提醒」的特別權限，要手動給。

**位置一直拿不到** — App 宣告了三條定位相關權限，各有理由：

- `ACCESS_FINE_LOCATION` — 助理問「你在哪」時抓一次精確位置
- `ACCESS_COARSE_LOCATION` — 讓使用者可以在授權對話框選「大概位置」，
  區級精度仍然回答得了「附近有什麼」，不該因此整個功能不能用
- `FOREGROUND_SERVICE_LOCATION` — 背景定期回報跑在既有的前景服務裡，
  服務要帶 location 型別才准抓

刻意**沒有** `ACCESS_BACKGROUND_LOCATION`：那是給「沒有前景服務也要在背景抓」
用的，需要使用者去系統設定手動選「一律允許」。這支 App 的背景連線本來就掛著
一個前景服務，靠它的 location 型別就夠了——授權對話框選
「僅在使用該應用程式時允許」即可，背景回報照樣會動。
