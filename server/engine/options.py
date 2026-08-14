"""SDK 選項組裝。

這支檔案裡每一個參數都是踩過坑換來的，改之前先讀註解。
"""
from __future__ import annotations

from pathlib import Path

from claude_agent_sdk import ClaudeAgentOptions, HookMatcher

import config
from protocol import Frontend

from . import agenda_tools, file_tools, persona
from .safety import make_pretool_hook
from .state import ConvState, eff_effort, eff_model

# 疊加在 Claude Code 預設 prompt 之上的規則。
#
# ⚠️ **絕對不可含換行符**。實測（SDK 0.1.81 / Windows）：append 內只要有一個 "\n"，
# initialize 的控制訊息就會損毀，CC 永遠完成不了握手，卡滿 60 秒後拋
# "Control request timeout: initialize"。用三個字元的 "A\nB" 即可穩定重現。
# 長度不是問題——四千字的 prompt 照跑，只要一個換行都沒有。
# 同理也不要嵌入錢字號、反引號、罕見 Unicode 符號，一律用文字描述規則。
# 護欄在 sanitize_append()，但別依賴它——知道為什麼比被擋下來重要。
#
# ── 這支檔案裡的規則 vs personas/*.txt ────────────────────────────────────────
# 底下四條是**功能性**的：拿掉服務就會壞。標記協定是 turn.py 判斷回合結束的
# 依據，缺了會反覆誤觸發補跑；自製工具不明講模型不會用。
# 語氣、個性、示範對話那些屬於人格，全部在 personas/ 底下，隨便改都不會弄壞東西。

# 1. 進度與控制標記——turn.py 的自動續跑依賴這兩個標記。
#
# 標記一定要寫出**字面值**。早期版本用文字描述「兩個中括號包住 DONE」，
# 模型理解成單層的 [DONE]，而正規表示式只認 [[DONE]]：標記沒被清掉直接洩漏到畫面上，
# 續跑判定也跟著失效，於是每次都多補跑一輪、再吐一個標記出來。
# 中括號本身是安全字元，不需要為它繞路。
_RULE_MARKERS = (
    "每個階段開始時用一句話說你正要做什麼，讓他在手機上跟得上。"
    "整件事真的做完時，在回覆的最後面單獨加上這個標記：[[DONE]]"
    "如果你問了問題正在等他回答，就改加上這個標記：[[WAIT]]"
    "標記要用兩層中括號，一字不差。這是給系統判讀的，他看不到，不要解釋也不要提起它們。"
)

# 2. 要他做選擇時，把選項變成手機上的按鈕。
#
# 這條取代了 CC 內建的 AskUserQuestion——那個工具**不在 SDK session 的工具清單裡**
# （實測 36 個工具全都沒有它，環境變數也叫不出來），模型看不到就不可能呼叫，
# 所以先前那條攔工具的路徑從來沒有觸發過一次。改走文字標記，跟 [[DONE]] 同一套機制。
#
# 標記一定要寫出**字面值**，理由同 _RULE_MARKERS。
_RULE_ASK_MARKER = (
    "需要他從幾個做法裡挑一個時，先用白話把狀況和你的建議講清楚，"
    "然後在回覆的最後面加上這個標記：[[ASK:問題|選項一|選項二]]"
    "直線符號隔開，第一段是問題本身，後面每一段是一個選項，給二到四個選項，"
    "每個選項用簡短的名詞或短句，不要寫成長句子，手機按鈕放不下。"
    "他的手機上會跳出按鈕讓他直接點，點下去的那個選項會變成你的下一則輸入。"
    "標記要用兩層中括號、一字不差，他看不到標記本身，不要解釋也不要提起它。"
    "打了這個標記就停在那裡等他點，不要繼續往下做、也不要自己幫他選一個然後動手，"
    "而且不要再加 [[DONE]] 或 [[WAIT]]，這個標記本身就代表你在等他回答。"
)

# 3. 行事曆／鬧鐘／記帳／課表。
#
# 這些是自製工具（in-process MCP，見 agenda_tools.py），不是 CC 內建的，
# 模型不會憑空知道它們存在——工具清單裡有歸有，不明講的話它遇到「幫我記一下」
# 還是會去建一個 txt 檔。這條就是在指路：什麼情況該想到這組工具。
_RULE_AGENDA = (
    "他的行事曆、鬧鐘、記帳、課表都在你手上，"
    "用 calendar 開頭、alarm 開頭、ledger 開頭、course 開頭的那組工具操作，"
    "不要自己去建檔案記，也不要叫他自己打開 App 輸入。"
    "他隨口提到的時間與金錢就直接記下來：說明天幾點要幹嘛就開行程，"
    "說幾點叫我起床就設鬧鐘，說花了多少錢就記帳，分類你自己判斷不用問他。"
    "記完用一句話帶過就好，不要複誦整筆資料。"
    "行程與鬧鐘的差別是會不會吵他：鬧鐘會在手機上大聲響，只有他要求叫他的時候才設。"
    "課表是一週固定重複的課，用 course_add 加、course_now 看他現在有沒有在上課；"
    "第幾節是幾點到幾點每個地方不一樣，他講了就用 period_set 改，別自己假設。"
    "要判斷現在方不方便打擾他、或是幫他排事情避開上課時間，先看 course_now，"
    "不要拿課表自己去對時間。"
)

# 4. 傳檔給手機。
#
# 同樣是自製工具（file_tools.py）。這條的重點在**時機**而不是用法：
# 模型預設會把成品留在磁碟上、回一句「檔案在 D:\... 」就結束，
# 而使用者人不在電腦前，那個路徑對他毫無用處。
_RULE_SENDFILE = (
    "你做出來的檔案他在手機上看不到，光報路徑等於沒給。"
    "圖片、報告、匯出的資料這種他會想直接看或存起來的東西，做完就用 send_file 傳給他，"
    "不用先問他要不要。純程式碼或設定檔就不必傳，貼在回覆裡比較快。"
)


def _amnesia_rule() -> str:
    """失憶自救。沒設 BUTLER_NOTES_FILE 就整條不加。

    伺服器重啟會殺掉 CC 進程，resume 回來時**最後一個回合接不回 context**：
    session id 沒變、逐字稿裡那段也寫進去了，但模型這邊就是沒有——
    檔案裡有，腦子裡沒有。

    觸發點刻意**不是**「重啟後」：失憶的人不知道自己失憶了，那個條件抓不到。
    唯一可靠的信號是使用者說「你剛剛才查過」。

    路徑在 prompt 裡一律轉成正斜線：反斜線要進 JSON 控制訊息，能少一層跳脫就少一層。
    """
    if not config.NOTES_FILE:
        return ""
    path = config.NOTES_FILE.replace("\\", "/")
    return (
        "你可能會忘記事情。這台電腦上的服務偶爾會讓你漏掉自己剛做完的一整段工作，"
        "而且你不會察覺，只會覺得那件事從來沒發生過。"
        "所以他說你剛剛才查過、你不是做完了、你又忘了這類話的時候，"
        "一律先假設他是對的，不要反駁、不要說自己沒忘、更不要回頭問他細節。"
        f"立刻去讀 {path}，最上面幾段就是最近幾輪做過的事，讀完再回話。"
        "要回報進度、要說某件事還沒做、或是要動這個專案的檔案之前也先讀它，"
        "那個檔比你的印象新。"
    )


# 助理對話：人格 + 全部核心規則。
SYSTEM_APPEND = (
    persona.load(config.PERSONA) + _amnesia_rule()
    + _RULE_AGENDA + _RULE_SENDFILE + _RULE_ASK_MARKER + _RULE_MARKERS
)

# ── 工作區分頁：不套人格 ──────────────────────────────────────────────────────
#
# 助理與工作區是兩個用途不同的東西，共用同一份 append 會讓工作對話也被灌上
# 語氣與行事曆記帳規則。
#
# 這份刻意只留「不留就會壞掉」的：平台事實（他在手機上看）、進度標記、
# 一次一問（ask 通道一次只收得到一個答案）。行事曆規則不進來——那是生活資料，
# 工作對話開著只會讓模型在「幫我記一下這個 bug」的時候把東西寫進記帳本。
# 其餘一律交還給 Claude Code 的預設行為，這才是「純工作」該有的樣子。
WORK_APPEND = (
    persona.load(config.WORK_PERSONA)
    + _RULE_SENDFILE + _RULE_ASK_MARKER + _RULE_MARKERS
)


def append_for(state: ConvState) -> str:
    """這條對話該套哪一份 system prompt。助理＝全套，其餘＝工作精簡版。"""
    return SYSTEM_APPEND if state.conv_id == config.PRIMARY_CONV else WORK_APPEND


def servers_for(state: ConvState) -> dict:
    """這條對話該掛哪些自製工具。

    行事曆／鬧鐘／記帳只給助理——那是生活資料，工作對話開著只會讓模型在
    「幫我記一下這個 bug」的時候把東西寫進他的記帳本。
    傳檔兩邊都要：工作做出來的圖表與報告一樣得送到他手機上。
    """
    if state.conv_id == config.PRIMARY_CONV:
        return {
            agenda_tools.SERVER_NAME: agenda_tools.SERVER,
            file_tools.SERVER_NAME: file_tools.SERVER,
        }
    return {file_tools.SERVER_NAME: file_tools.SERVER}


def sanitize_append(text: str) -> str:
    """清掉會弄壞 init 握手的字元。

    這是確定性護欄，不是禮貌提醒——人格檔與 plugin 都會貢獻
    system_prompt_append 文字，只靠註解叮嚀「不要換行」遲早會有人踩到，
    而症狀是「卡 60 秒後 timeout」，完全不指向換行符。
    """
    return " ".join(text.split())


def thinking_off(state: ConvState) -> dict:
    """關思考逃生門要送的 thinking 設定，依模型分流。純函式，供測試直接驗。

    非 Fable／Mythos 送 disabled——實測只有它能真的關掉思考（省略參數僅把 display
    退回 omitted，模型照樣思考）。
    Fable／Mythos 思考強制開啟、收到 disabled 會 400，只能退回 adaptive；
    保留 summarized 讓思考摘要仍拿得到，空回覆的第三層兜底才有素材可用。
    """
    m = (eff_model(state) or "").lower()
    if "fable" in m or "mythos" in m:
        return {"type": "adaptive", "display": "summarized"}
    return {"type": "disabled"}


def build_options(state: ConvState, frontend: Frontend) -> ClaudeAgentOptions:
    """依對話狀態組出 ClaudeAgentOptions（建立長駐 client 時用一次）。"""
    # cwd 防護：切換歷史 session 可能帶入已不存在的目錄（WinError 267），退回預設
    if not Path(state.cwd).is_dir():
        state.cwd = config.DEFAULT_CWD
    options = ClaudeAgentOptions(
        cwd=str(state.cwd),
        cli_path=config.CLAUDE_CLI,
        model=eff_model(state),
        effort=eff_effort(state),
        # 維持全放行（自動執行，不干擾工作流）。危險指令確認改掛 PreToolUse hook：
        # 實測 headless/SDK 下 can_use_tool 回呼不會被觸發，但 PreToolUse hook 即使在
        # bypassPermissions 也照樣觸發、且 permissionDecision="deny" 能真正擋下工具。
        permission_mode="bypassPermissions",
        hooks={"PreToolUse": [HookMatcher(
            matcher="Bash|PowerShell|AskUserQuestion",
            hooks=[make_pretool_hook(state, frontend)],
        )]},
        # 行事曆／鬧鐘／記帳走 in-process MCP：跟服務同一個進程，沒有 IPC 開銷，
        # 也不必再開一個子進程去守一份 JSON 檔。
        # 刻意不設 allowed_tools——那是白名單，一設下去 CC 內建工具全被擋掉。
        mcp_servers=servers_for(state),
        fallback_model=config.FALLBACK_MODEL,
        max_buffer_size=config.MAX_BUFFER_SIZE,
        # system_prompt 用 preset+append：自訂規則以「疊加」方式放在 Claude Code 完整
        # 預設 prompt 之上，保留預設行為框架與 CLAUDE.md 的開場注入。
        # 傳純字串會整份「取代」預設 prompt，CC 因此缺所有預設行為規則，
        # 只剩碰運氣的 nested-memory 附帶、壓縮後歸零。
        # append 一律過 sanitize_append：含換行會讓 init 握手卡死 60 秒（見上方說明）
        system_prompt={
            "type": "preset",
            "preset": "claude_code",
            "append": sanitize_append(append_for(state)),
        },
        # 思考摘要：Opus 4.7+ 預設 display="omitted"（只回簽章、沒有文字），這是
        # 「思考中」永遠空白的根因。改 summarized 才拿得到思考文字。
        #
        # 必須用 adaptive，不能用 enabled：SDK 對 type=="enabled" 無條件讀
        # t["budget_tokens"] 組 --max-thinking-tokens，沒帶就 KeyError，每次建 client
        # 必炸；而 budget_tokens 自 Opus 4.7 起已從 API 移除，現代模型收到它一律 400
        # ——補上鍵也無解，enabled 就是死路。
        thinking=(thinking_off(state) if state._no_think
                  else {"type": "adaptive", "display": "summarized"}),
    )
    # 逐字串流：讓生成中的思考／回應能即時送出，也是回合仍活著的訊號
    options.include_partial_messages = True
    return options
