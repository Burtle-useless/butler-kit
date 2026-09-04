package dev.butlerkit.app.net

import android.util.Log
import org.json.JSONObject

/** 這台手機與電腦那端的服務：回報位置、服務狀態與重啟、已配對裝置。 */
interface DeviceApi {
    suspend fun reportLocation(body: JSONObject): Result<Unit>
    suspend fun systemStatus(): Result<SystemStatus>
    suspend fun restartSystem(): Result<Unit>
    suspend fun listDevices(): Result<List<DeviceInfo>>
    suspend fun revokeDevice(hash: String): Result<Unit>
}

internal class DeviceApiImpl(core: ClientCore) : DeviceApi, ClientCore by core {

    /**
     * 回報目前位置（含抓不到時的原因）。
     *
     * 失敗只記一行不重試：伺服器那端有自己的逾時，而位置過幾秒就不是同一回事了，
     * 重送一筆舊座標比不送更糟。
     */
    override suspend fun reportLocation(body: JSONObject): Result<Unit> =
        post("/v1/device/location", body).onFailure {
            Log.w(ButlerClient.TAG, "回報位置失敗：${it.message}")
        }

    /** 電腦上那個服務的現況：跑多久了、正在跑哪一版。 */
    override suspend fun systemStatus(): Result<SystemStatus> = getJson("/v1/system")
        .mapCatching {
            SystemStatus(
                pid = it.optInt("pid"),
                uptime = it.optString("uptime"),
                commit = it.optString("commit"),
                subject = it.optString("subject"),
                latestCommit = it.optString("latest_commit"),
                latestSubject = it.optString("latest_subject"),
            )
        }

    /**
     * 請電腦重啟服務。
     *
     * 回應會趕在服務停掉之前送出來（腳本會先等到沒人在串流才動手），
     * 所以拿到成功不代表已經重啟完，只代表「排進去了」。
     */
    override suspend fun restartSystem(): Result<Unit> =
        post("/v1/system/restart", JSONObject())

    // ── 已授權的裝置 ──────────────────────────────────────────────────────────

    /**
     * 列出所有配對過的裝置。
     *
     * 伺服器已經照 created 排好，這裡不再排。`this` 那一台由伺服器判定
     * （它比對的是這次請求用的 token），App 自己算不出來也不該算。
     */
    override suspend fun listDevices(): Result<List<DeviceInfo>> = getJson("/v1/devices")
        .mapCatching { o ->
            val arr = o.optJSONArray("devices") ?: return@mapCatching emptyList()
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DeviceInfo(
                    hash = d.optString("hash"),
                    short = d.optString("short"),
                    name = d.optString("name"),
                    created = d.optDouble("created", 0.0),
                    // last_seen 只活在伺服器記憶體裡，服務重啟就回到 null
                    lastSeen = if (d.isNull("last_seen")) null
                               else d.optDouble("last_seen"),
                    isThis = d.optBoolean("this"),
                )
            }
        }

    /**
     * 撤銷一台裝置，立即生效。
     *
     * 伺服器擋掉「撤銷自己這台」（400），所以這裡不必自己防——但 UI 仍然不給
     * 那一列撤銷鈕，因為讓人按下去再被拒絕是很差的解釋方式。
     */
    override suspend fun revokeDevice(hash: String): Result<Unit> =
        post("/v1/devices/$hash/revoke", JSONObject())
}
