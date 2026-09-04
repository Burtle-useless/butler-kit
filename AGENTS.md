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

## 這東西長什麼樣（動手改之前先讀）

電腦端分三層，**依賴只往下不往上**：

```
transport/   FastAPI：SSE、REST、驗證、上傳下載、裝置配對
protocol/    事件格式（make_event）與 Frontend 這個抽象前端
engine/      回合、session、工具、人格、狀態。不認識 HTTP
```

分這一刀的用途是「同一份 engine 能被第二個前端接上」。要接自己的前端
（另一支 bot、一支 CLI、一個網頁殼）時，這三個檔是入口，其餘不用動：

| 檔 | 它是什麼 |
|---|---|
| `engine/worker.py` | **一條對話一個 `Worker`**：排隊、合併、插話、停止、wake 回合、限流自動續跑。做成類別不是模組全域，所以兩個前端各持一份互不干擾。你只要餵它一個 `frontend_for(conv_id)` |
| `engine/ports.py` | engine 需要外界做的事（推播、跟手機要位置、送檔案、資料變了要通知）收成一個 **frozen dataclass，欄位全部必填**。少接一條在建構時就 TypeError，不會變成上線後「鬧鐘不響、位置永遠拿不到」這種靜默故障。`install()` 是唯一接線入口 |
| `engine/profiles.py` | 哪條對話用哪份人格（system prompt append）與哪些 MCP 工具。**conv_id 前綴註冊表，最長前綴優先，`""` 是預設。** 助理那條走精確比對不走前綴——否則一條叫 `main2` 的工作對話會被當成助理 |

`transport/` 也按領域拆成 `conversations_api` / `settings_api` / `device_api` /
`files_api` / `agenda_api` / `kanban_api` / `devices_api` / `outbox_api` /
`system_api`，加上共用的 `app.py`（組裝與生命週期）、`auth.py`、`hub.py`（SSE 廣播）、
`files.py`（磁碟層）。加一組路由就多一個檔，不要塞回 `app.py`。

要自己寫前端的話，**附件那條路有一個容易做錯的地方**：`POST /v1/conversations/
{id}/message` 的 `attachments` 是**結構化欄位**（`[{name, path, bytes, mime}]`），
不要把路徑拼進 `text` 送出去。路徑由伺服器在組給模型的那一份接上（`turn.stamp`，
跟時間戳同一個道理），所以使用者的氣泡裡不會出現它，畫面照欄位畫縮圖與檔案卡。
先送 `POST /v1/uploads` 拿到 `path` 與 `name`，`GET /v1/uploads/{name}` 再把檔案
取回來畫預覽——**不要靠客戶端自己的本地檔案參照**，那份換一台裝置就沒了。

伺服器會把 `attachments` 的路徑**限制在上傳目錄底下**（`transport/app.py` 的
`_clean_attachments`）。這是安全性不是潔癖：這個欄位是客戶端給的，直接信任等於
讓任何配對過的裝置指定一個路徑叫模型去讀，那是整台電腦的任意檔案。

兩件跟模型有關、會影響你怎麼回答使用者的事：

- **模型清單是向 CLI 要的**（`engine/models.py`），不是寫死的。CLI 的 initialize
  回應本來就帶 `models`，`value` 是官方別名（`default`／`opus`／`sonnet`／`haiku`…），
  直接當 `ClaudeAgentOptions.model` 用。清單快取在 `data/models.json`，一小時更新一次。
  **CLI 升版就自己長出新模型，不要回頭改程式碼。** `DEFAULT_MODEL` 只是預設值。
- **撞到 rate limit 會自動接回去**（`engine/errors.py` 的 `AUTO_RESUME_MAX_SEC`，
  預設 6 小時）：CLI 有給回復時刻、而且在門檻內的話，訊息留在佇列裡等額度回來
  自動重跑；超過門檻（例如週額度要等三天）才回報給使用者自己決定。

---

## 環境需求

**電腦端（server）**
- Python 3.11+
- Claude Code CLI 已安裝且已登入（`claude` 指令跑得動）
- CLI 的位置不用設定：SDK 會用自己 wheel 裡帶的那支，版本與 SDK 同批。
  真要指定別的才設 `CLAUDE_CLI` 環境變數，**且必須指向原生執行檔**——
  Windows 上不能填 npm 裝出來的 `claude.cmd`，SDK 拒絕 spawn 批次檔
  （cmd.exe 執行 `.bat`/`.cmd` 時參數可被注入），填了會每個回合都在
  連線階段失敗，而畫面上只顯示一句「出了點狀況」。

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
CLAUDE_CLI=...              # 留空＝用 SDK 自帶的。要填就填原生執行檔，不能是 .cmd
DEFAULT_MODEL=claude-sonnet-4-6   # 預設模型。可選的清單由 CLI 給，見上面的架構那節
DEFAULT_EFFORT=medium       # 預設思考等級（low/medium/high/xhigh/max）
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

> debug build 另有一份 `app/app/src/debug/res/xml/network_security_config.xml`，
> **它會整份蓋掉 main 那份**，所以 main 加了什麼這裡也要加一次。它比 main 多的
> 只有模擬器看出去的 `10.0.2.2`。漏掉一條的症狀是「debug 版連得上、release 版
> 連不上」，反過來也會發生。

走 Cloudflare Tunnel 而且在通道前面加了 Access 的話，服務憑證從
`app/local.properties` 注入（`cfAccessClientId` / `cfAccessClientSecret`），
由 `net/AccessAuth.kt` 加到每個請求的標頭上。**不要把憑證寫進原始碼**——
那個檔在 `.gitignore` 裡，留空的話一個標頭都不加，其他連線方式不受影響。

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

改的地方只有三個檔，其餘 70 幾個檔案不用動：

| 要改什麼 | 改哪裡 |
|---|---|
| 顏色、圓角、字級、預設字型 | `app/.../ui/Theme.kt`（`LightColors`／`DarkColors`、`Radii`、`Type`、`Fonts`） |
| 吉祥物 | `app/.../ui/PetBot.kt`（只要保住 `PetMood` 七個狀態與 `PetFace` 的簽名，裡面整支換掉都行） |
| App 名稱、通知標題 | `app/.../res/values/strings.xml` 的 `app_name` |

兩個檔的檔頭註解都寫了怎麼改與該注意什麼，動手前先讀。

**色票有淺深兩份，兩份都要挑。** 主題跟隨系統日夜（`ButlerTheme` 讀
`isSystemInDarkTheme()`），畫面上一律讀 `Palette.*`，那些是 composable getter，
指到當前那份 `Colors`——所以換色票不必碰任何一個引用點。三個會踩到的地方：

- **`Palette.*` 只能在 composable 裡讀。** Canvas 的 draw lambda、`remember {}`
  的計算式、enum 建構子都不行；那些地方先在外面 `val p = LocalPalette.current`
  再帶進去。
- **深色不是把淺色的值反過來。** 底不要用純黑（彩色會過度躍出），強調色與語意色
  要提亮一階，否則深底上的深色元件會直接沉掉。
- **啟動畫面與系統列不歸 Compose 管**：`res/values/themes.xml` 與
  `res/values-night/themes.xml`（外加 `-v27`／`-v31` 四份）各有一份，`@color/bg`
  要跟兩份色票的 `bg` 對齊。漏了深色那份的症狀是深色模式冷啟動先閃一幕白。

桌面 widget 的色票是**另一份抄本**（`widget/WidgetUi.kt`）：Glance 是 RemoteViews，
拿不到 App 的 CompositionLocal，換色票時那邊要手動跟上。

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
| 套件名 | `app/app/build.gradle.kts` 的 `namespace` 與 `applicationId`，以及 80 幾個 `.kt` 檔的 `package` 宣告與目錄結構 |
| 圖示 | `res/drawable/ic_launcher_foreground.xml` 與 `ic_notify.xml`。兩個都是佔位的一顆點，**換吉祥物時順手換掉**，否則桌面與通知列的圖示會跟 App 裡的不一樣 |

---

## 換人格

人格在 `server/personas/*.txt`，是**純文字檔不是程式碼**。附了三份：

- `default.txt` — 中性起點。助理那條用，換檔名設 `BUTLER_PERSONA`
- `example-tsundere.txt` — 有個性的完整範例，重點在示範**結構**
- `work.txt` — 工作對話用，不套個性。換檔名設 `BUTLER_WORK_PERSONA`

**哪條對話拿哪一份是 `engine/profiles.py` 決定的**（見上面架構那節），
換語氣不必碰它——那兩個環境變數管的就是「內建那兩份 append 各讀哪個檔」。

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
cd app && ./gradlew.bat assembleDebug testDebugUnitTest
```

改到 server 就整包跑一輪（41 支，扣掉要起服務的那支是 40 支，幾十秒）：

```bash
cd server
for %f in (tests\test_*.py) do python %f      # POSIX: for f in tests/test_*.py; do python "$f"; done
```

三條規矩：

- **測試檔一支一支直接用 python 跑。** 它們是腳本風格——`check(name, cond)`
  印 PASS／FAIL 並記進模組級的 `FAILED`，`main()` 最後才依它回 exit code。
  pytest 收集 `test_*` 函式時裡面一個 assert 都沒有，**每一條都會是綠的**。
  `tests/conftest.py` 已經加了一層對照把這件事補起來（pytest 也會紅），
  但直接跑檔案仍然是最不會被騙的方式。
- **先設 `BUTLER_DATA_DIR` 指到暫存目錄**，否則測試會寫進正式的 `data/`。
  conftest 會 `setdefault` 一個，但那只在走 pytest 時生效。
- `test_e2e_sse.py` 要有一個跑著的 server 才會過，沒起服務時它失敗是正常的，
  整包跑的時候跳過它。
