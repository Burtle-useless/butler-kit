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
| `找不到 Tailscale 位址…拒絕啟動` | 沒裝 Tailscale 或沒登入 | 見步驟二。走通道的話設 `BUTLER_BIND=127.0.0.1`；只想驗服務本身用 `BUTLER_DEV=1` |
| `BUTLER_BIND=… 是萬用位址，拒絕啟動` | 設了 `0.0.0.0` 之類 | 見下面那條紅線，改綁 `127.0.0.1` 走通道 |
| `已被佔用` | 另一個實例還在跑 | 關掉它，或設 `BUTLER_PORT` |
| 卡住 60 秒後 `Control request timeout: initialize` | system prompt append 裡有換行符 | 見下面「人格」那節 |

常用環境變數（全部可選）：

```
BUTLER_PORT=47362           # 監聽埠
BUTLER_BIND=127.0.0.1       # 監聽位址。留空＝自動找 Tailscale 位址；走通道就填這個
BUTLER_DEV=1                # 強制綁 127.0.0.1，只在本機驗證時用
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
所以位址怎麼變都不必重編。**沒有特別想法就照這個走**：

1. 電腦與手機都裝 Tailscale，登入同一個帳號
2. 電腦端開 MagicDNS，記下主機名（像 `desktop-abc.tailXXXX.ts.net`）
3. App 的 host 填 `那個主機名:47362`

**已經有網域的話走通道更省事**（Cloudflare Tunnel、ngrok 之類）：那是 https，
根本碰不到明文白名單，**不用重編 APK**，手機端也不必裝 VPN。

1. 通道工具跑起來，指向 `http://127.0.0.1:47362`
2. server 設 `BUTLER_BIND=127.0.0.1`（它就不會去找 Tailscale 了）
3. App 的 host 填那個 https 網域

這樣服務只綁本機，連同一個區網的其他裝置都掃不到，比綁 tailnet 位址更緊。
代價是通道的入口是公開的——**務必在通道那層加驗證**（Cloudflare Access 之類），
不要只靠 device token。

其他選法與各自的代價寫在 `docs/transport.md`。要改成區網明文，
在那份 XML 裡加一筆 `<domain>`、server 設 `BUTLER_BIND=<區網 IP>`，再重編。

> **紅線：`BUTLER_BIND` 不接受 `0.0.0.0`，設了會直接拒絕啟動。** 引擎跑在
> bypassPermissions 底下，打得到這個 port 就等於能對那台電腦下任意指令。
> 要從外面連進來請走通道，不要直接對外開。

---

## 步驟三：外觀（請不要跳過）

**這個 repo 附的外觀是佔位用的，不是成品。** 灰白配色，吉祥物的位置上只有
一顆會呼吸、會依狀態變色的點——能跑、看得出現在是什麼狀態，但刻意做得毫無
個性。那顆點就是留給吉祥物的空位，原封不動編出去的話，使用者的 App 會跟每一
支沒改過的 butler-kit 長得一模一樣。

這是這份 kit 唯一**要求你先跟使用者討論**的一步。不要自己挑一套顏色就開始編，
也不要去抄別人做好的樣子（原作者那套報紙風就不在這個 repo 裡，那是他的）。
問清楚再動手，至少要問到這三件事：

1. **這支 App 給誰用、在什麼情境看？** 深夜在床上看跟白天在辦公室看，
   底色亮度差很多。這比「喜歡什麼顏色」更能決定色票。
2. **吉祥物要不要有？長什麼樣？** 它是使用者判斷「電腦那邊在幹嘛」的主要
   訊號，七個狀態都要分得出來。不想要角色感的話，換成純圖形指示器也行。
3. **硬的還是軟的？** 直角＋細線是工具感，圓角＋大留白是親切感。
   這個決定要一次貫穿全 App，混著用會顯得沒想清楚。

改的地方只有三個檔，其餘 40 幾個檔案不用動：

| 要改什麼 | 改哪裡 |
|---|---|
| 顏色、圓角、字級、預設字型 | `app/.../ui/Theme.kt`（`Palette`、`Radii`、`Type`、`Fonts`） |
| 吉祥物 | `app/.../ui/PetBot.kt`（只要保住 `PetMood` 七個狀態與 `PetFace` 的簽名，裡面整支換掉都行） |
| App 名稱、通知標題 | `app/.../res/values/strings.xml` 的 `app_name` |

兩個檔的檔頭註解都寫了怎麼改與該注意什麼，動手前先讀。

**不設計也編得起來**——這一步不會擋住你。但編出來之後請告訴使用者外觀還是
預設值，讓他決定要不要現在弄。

---

## 步驟四：編 APK

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

## 步驟五：配對

App 首次啟動會進設定頁：

- **host** — 步驟二決定的位址加埠，例如 `desktop-abc.tailXXXX.ts.net:47362`
- **token** — 步驟一印出來的那串

沒印出來（不是第一次啟動）就刪掉 `server/data/devices.json` 重啟，會重新產一個。

---

## 換品牌

顏色、吉祥物、App 名稱在步驟三，這裡是剩下兩項比較費工的：

| 要改什麼 | 改哪裡 |
|---|---|
| 套件名 | `app/app/build.gradle.kts` 的 `namespace` 與 `applicationId`，以及 46 個 `.kt` 檔的 `package` 宣告與目錄結構 |
| 圖示 | `res/drawable/ic_launcher_foreground.xml` 與 `ic_notify.xml`。兩個都是佔位的一顆點，**換吉祥物時順手換掉**，否則桌面與通知列的圖示會跟 App 裡的不一樣 |

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
