# 給 AI 助理的部署指南

你正在幫使用者部署 butler-kit：一個 Android App，讓他從手機驅動這台電腦上的
Claude Code。你要做的是把 server 跑起來、編一支 APK 給他裝、然後配對。

照順序讀完再動手。這份文件裡的每個坑都是踩過的。

---

## 先講清楚這東西有多危險

**這支 App 給手機端一台電腦的完整控制權。**

- 引擎跑在 `permission_mode="bypassPermissions"`。模型要刪檔、要跑 PowerShell、
  要讀任何檔案，都不會跳出來問。這是刻意的設計——使用者人在外面，不可能一直點確認。
- 所以 **device token 外流 = 這台電腦被接管**。token 存在 `server/data/devices.json`
  （只存 SHA256），明文只在首次啟動時印一次。
- server **永遠不綁 `0.0.0.0`**，`config.resolve_bind_host()` 會直接拒絕啟動。
  不要為了「先讓它動起來」去改這條。公用 Wi-Fi 上綁 0.0.0.0 等於把電腦交出去。

要動這三件事的任何一件之前，先問使用者。他可能不知道自己在同意什麼。

---

## 環境需求

**電腦端（server）**
- Python 3.11+
- Claude Code CLI 已安裝且已登入（`claude` 指令跑得動）
- Windows：`config.CLAUDE_CLI` 預設找 `%APPDATA%\npm\claude.cmd`，
  裝在別處就設 `CLAUDE_CLI` 環境變數。macOS / Linux 一律要設。

**編 APK**
- JDK 17 或 21
- Android SDK（`ANDROID_HOME` 或 `app/local.properties` 的 `sdk.dir`）
- Gradle 不必另外裝，用 repo 附的 `app/gradlew`

**⚠️ 專案路徑不能有非 ASCII 字元。** Android Gradle Plugin 會直接拒編，
錯誤訊息是 `Your project path contains non-ASCII characters`。中文資料夾名是最常見的原因。

---

## 步驟一：把 server 跑起來

```bash
cd server
python -m venv .venv
.venv\Scripts\activate          # Windows；POSIX 用 source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

第一次啟動會印出 device token，**只印這一次**，記下來。

起不來的話看這幾條：

| 症狀 | 原因 | 處理 |
|---|---|---|
| `找不到 Tailscale 位址…拒絕啟動` | 沒裝 Tailscale 或沒登入 | 見步驟二，或先用 `BUTLER_DEV=1` 綁 loopback 驗證服務本身 |
| `已被佔用` | 另一個實例還在跑 | 關掉它，或設 `BUTLER_PORT` |
| 卡住 60 秒後 `Control request timeout: initialize` | system prompt append 裡有換行符 | 見下面「人格」那節 |

常用環境變數（全部可選）：

```
BUTLER_PORT=47362           # 監聽埠
BUTLER_DEV=1                # 允許綁 127.0.0.1，只在本機驗證時用
BUTLER_CWD=C:\path\to\work  # Claude Code 的預設工作目錄，預設是家目錄
BUTLER_PERSONA=default      # 人格檔名，對應 server/personas/<名字>.txt
BUTLER_NOTES_FILE=...       # 「失憶自救」要讀的筆記檔，留空＝關掉這條規則
BUTLER_PLAN=max             # 訂閱方案，填 max/team 高階模型才拿得到 1M context
CLAUDE_CLI=...              # Claude Code CLI 的完整路徑
DEFAULT_MODEL=claude-sonnet-4-6
```

---

## 步驟二：決定怎麼連線（這一步決定要不要重編 APK）

手機要連到這台電腦。Android 9 以後預設**禁止明文 HTTP**，只有
`app/app/src/main/res/xml/network_security_config.xml` 白名單裡的目的地能走。

**這份白名單是編譯期資源，執行期換不了，而且不吃 CIDR。** 這是整個部署最容易
卡住的一點——選錯了要重編 APK。

預設值是 Tailscale：白名單只寫 `ts.net`（`includeSubdomains=true`），
所以位址怎麼變都不必重編。**除非使用者有特殊理由，就照這個走**：

1. 電腦與手機都裝 Tailscale，登入同一個帳號
2. 電腦端開 MagicDNS，記下主機名（像 `desktop-abc.tailXXXX.ts.net`）
3. App 的 host 填 `那個主機名:47362`

其他選法與各自的代價寫在 `docs/transport.md`。要改成區網明文，
在那份 XML 裡加一筆 `<domain>` 再重編。

---

## 步驟三：編 APK

```bash
cd app
./gradlew assembleDebug          # Windows 用 gradlew.bat
```

產物在 `app/app/build/outputs/apk/debug/app-debug.apk`。

- debug 版就夠用（側載安裝，不上架）。要出 release 版得自己建 keystore，
  `.gitignore` 已經擋掉 `*.jks` 與 `keystore.properties`，別把它們提交上去。
- 首次編譯要下載相依，會花幾分鐘。
- 沒有 `local.properties` 的話，先建一個寫 `sdk.dir=<Android SDK 路徑>`。
  這個檔不進版控。

APK 傳到手機安裝（需要允許安裝未知來源）。

---

## 步驟四：配對

App 首次啟動會進設定頁：

- **host** — 步驟二決定的位址加埠，例如 `desktop-abc.tailXXXX.ts.net:47362`
- **token** — 步驟一印出來的那串

沒印出來（不是第一次啟動）就刪掉 `server/data/devices.json` 重啟，會重新產一個。

---

## 換品牌

| 要改什麼 | 改哪裡 |
|---|---|
| App 名稱、通知標題 | `app/app/src/main/res/values/strings.xml` 的 `app_name` |
| 套件名 | `app/app/build.gradle.kts` 的 `namespace` 與 `applicationId`，以及 46 個 `.kt` 檔的 `package` 宣告與目錄結構 |
| 配色 | `app/app/src/main/java/dev/butlerkit/app/ui/Theme.kt` 的 `object Palette`，18 個色值全在這裡 |
| 圖示 | `res/drawable/ic_launcher_foreground.xml` 與 `ic_notify.xml` |
| 桌寵 | `ui/PetBot.kt`。整支換掉就好，對外只暴露 `PetMood` 與 `PetFace()` |

---

## 換人格

人格在 `server/personas/*.txt`，是**純文字檔不是程式碼**。設
`BUTLER_PERSONA=<檔名>` 切換。附了三份：

- `default.txt` — 中性起點
- `example-tsundere.txt` — 有個性的完整範例，重點在示範**結構**
- `work.txt` — 工作對話用，不套個性

寫法與踩過的坑在 `docs/persona.md`。**動手改之前務必讀那份**，特別是這條：

> system prompt 的 append 裡只要有一個換行符，SDK 的 init 握手就會損毀，
> CC 永遠完不成握手，卡滿 60 秒後拋 `Control request timeout: initialize`。
> 錯誤訊息完全不指向換行符。

`options.sanitize_append()` 會把整份壓成單行擋住這件事，但別依賴它——
知道為什麼比被擋下來重要。

至於 `engine/options.py` 裡剩下的那幾條規則（進度標記、選項標記、自製工具說明），
那些是**功能性**的：拿掉服務就會壞。`[[DONE]]` / `[[WAIT]]` 是 `turn.py` 判斷回合
結束的依據，缺了會反覆誤觸發補跑。不要把它們搬進人格檔。

---

## 驗證

改完跑這兩組，都要全綠：

```bash
cd server && python tests/test_profiles.py     # 人格分流
cd app && ./gradlew testDebugUnitTest
```

`server/tests/` 下的測試**直接用 python 跑**，不要用 pytest——
它們用 `check()` 收集結果而不是 assert，pytest 跑起來永遠是綠的。

`test_e2e_sse.py` 要有一個跑著的 server 才會過，沒起服務時它失敗是正常的。
