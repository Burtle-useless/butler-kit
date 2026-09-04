package dev.butlerkit.app.notify

/**
 * 檔案通知的閘門：回合跑到一半傳過來的檔案先按住，等這則訊息真的收工再一起發。
 *
 * **為什麼需要。** `file.offer` 是助理一呼叫傳檔工具就發的，而它傳完檔案往往還要
 * 接著做事、講收尾——實測有過傳檔到定稿差七分半鐘。使用者收到「助理傳了 xx 給你」
 * 立刻過來，看到的是還在思考的畫面。他的原話：「東西都給完了再跳通知」。
 *
 * 「做完了」那種通知先前也犯過同一個錯（綁在每輪都發的 `reply.final` 上），
 * 已經改綁 `turn.done`；這裡是同一個問題的另一條路徑。
 *
 * 狀態只有一份且只在事件迴圈那個協程裡進出，所以不加鎖。
 */
class FileNotifyGate {

    /** 現在有回合在跑的對話。 */
    private val running = mutableSetOf<String>()

    /** 按住還沒通知的檔名，key 是對話 id。 */
    private val held = mutableMapOf<String, MutableList<String>>()

    /**
     * 回合開始了。
     *
     * @return 上一輪沒收好、現在必須補發的檔名。正常情況是空的——會有東西代表
     *   上一輪的收工事件不見了（伺服器重啟、連線斷在半路都會），沒有這道保險
     *   那些檔案就永遠靜靜留在清單裡沒人知道。
     */
    fun onTurnStart(conv: String): List<String> {
        val left = held.remove(conv).orEmpty()
        running += conv
        return left
    }

    /**
     * 心跳說這個對話還在跑。
     *
     * 光靠 `turn.start` 不夠：背景連線是從上次的游標續傳的，斷層太大時伺服器會叫
     * 前端改拉快照，那一則就這樣錯過了——`running` 於是永遠是空的，整個閘門形同
     * 不存在（而且是靜默的，表現得跟修好之前一模一樣）。心跳每兩秒一次，
     * 錯過幾則也補得回來。
     */
    fun onRunning(conv: String) {
        running += conv
    }

    /**
     * 助理傳了一個檔案過來。
     *
     * @return 現在就該通知的檔名。空的代表按住了，等收工再發。
     */
    fun onFile(conv: String, name: String): List<String> {
        if (conv !in running) return listOf(name)
        held.getOrPut(conv) { mutableListOf() }.add(name)
        return emptyList()
    }

    /**
     * 回合結束——收工或出錯都算。
     *
     * 出錯那條**一定要呼叫**：出錯的回合不發 `turn.done`（伺服器刻意的），
     * 漏掉的話這個對話會永遠被當成跑中，之後的檔案通知全部被吞掉。
     *
     * @return 該一起發的檔名。
     */
    fun onTurnEnd(conv: String): List<String> {
        running -= conv
        return held.remove(conv).orEmpty()
    }
}
