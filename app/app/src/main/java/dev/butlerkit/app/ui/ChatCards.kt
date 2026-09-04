package dev.butlerkit.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.ApkUpdate
import dev.butlerkit.app.data.InboxRepo
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.OfferedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 對話設定面板：cc-bot 的 `/status`＋`/model_session`＋`/effort_session` 合成一頁。
 *
 * 這些在 Discord 是三個要打字的指令，在這裡是頂欄一顆⚙。**覆寫是 per-conv 的**——
 * 「這條對話用 Opus 想深一點、其他維持 Sonnet」在 cc-bot 上是常用操作，
 * App 端先前完全沒有入口，只能改全域預設把所有對話一起換掉。
 */
@Composable
internal fun ConvSettingsDialog(
    state: ChatState,
    onApply: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = state.convStatus
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉", color = Palette.Accent) }
        },
        containerColor = Palette.Surface,
        titleContentColor = Palette.Text,
        textContentColor = Palette.TextDim,
        title = {
            Text("這條對話", fontSize = Type.Title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cs == null) {
                    Text("還沒拿到狀態。連上電腦之後再開一次。", fontSize = Type.Meta)
                    return@Column
                }
                CtxBar(cs.ctxTokens, cs.ctxLimit)
                Column {
                    Text("工作目錄", color = Palette.TextDim, fontSize = Type.Tiny)
                    Text(
                        cs.cwd.ifBlank { "（預設）" },
                        color = Palette.Text, fontSize = Type.Tiny,
                        fontFamily = FontFamily.Monospace, maxLines = 2,
                    )
                }
                // 模型與思考強度：一列摘要，點了開跟設定頁同一個面板（見 ModelPicker.kt）
                val infos = state.settings?.infos.orEmpty()
                var pick by remember { mutableStateOf(false) }
                val modelText = modelDisplay(cs.modelOverride.ifBlank { cs.model }, infos) +
                    if (cs.modelOverride.isBlank()) "（跟隨預設）" else ""
                ModelSummaryRow(
                    modelText = modelText.ifBlank { "（尚未取得）" },
                    effortText = cs.effortOverride.ifBlank { cs.effort }.ifBlank { "預設" } +
                        if (cs.effortOverride.isBlank()) "（跟隨預設）" else "",
                ) { pick = true }
                if (pick) {
                    ModelPickerSheet(
                        infos = infos,
                        efforts = state.settings?.efforts.orEmpty(),
                        selectedModel = cs.modelOverride,
                        selectedEffort = cs.effortOverride,
                        followLabel = "跟隨帳號預設",
                        effectiveModel = cs.model,
                        effectiveEffort = cs.effort,
                        onPickModel = { onApply(it, null) },
                        onPickEffort = { onApply(null, it) },
                        onDismiss = { pick = false },
                    )
                }
            }
        },
    )
}

/** context 用量長條。分母由伺服器給（隨模型變），不在前端寫死。 */
@Composable
private fun CtxBar(tokens: Int, limit: Int) = Column {
    val frac = if (limit > 0) (tokens.toFloat() / limit).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("context", color = Palette.TextDim, fontSize = Type.Tiny)
        Text(
            "%,d / %,d".format(tokens, limit),
            color = Palette.TextDim, fontSize = Type.Tiny,
            fontFamily = FontFamily.Monospace,
        )
    }
    Box(
        Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp)
            .background(Palette.SurfaceHi, Radii.Chip),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight()
                // 逼近 0.85 就會觸發自動壓縮，先變色當預告
                .background(if (frac > 0.85f) Palette.Danger else Palette.Accent, Radii.Chip),
        )
    }
}

/**
 * 助理傳來的一個檔案。
 *
 * 圖片直接畫在對話裡——要先按下載才看得到的圖，跟沒傳給你差不多。
 * 預覽的 bytes 走的是跟存檔同一個端點（伺服器沒有縮圖端點），所以只對
 * [PREVIEW_MAX_BYTES] 以內的圖這麼做，大圖一律先給取件單。
 */
@Composable
internal fun FileOfferCard(item: TraceItem.FileOffer, client: ButlerClient) {
    val ctx = LocalContext.current
    // key 用 fileId：LazyColumn 回收重用時，狀態不可以跟著位置留給下一個檔案
    var preview by remember(item.fileId) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var previewFailed by remember(item.fileId) { mutableStateOf(false) }
    // 存檔狀態放 InboxRepo 不放這裡：這張卡在 LazyColumn 裡會被回收，
    // 用 remember 存的話滑出去再滑回來就忘了自己存過，還會看到「存檔中…」歸零
    val downloading by InboxRepo.downloading.collectAsState()
    val savedMap by InboxRepo.saved.collectAsState()
    val stagedSet by InboxRepo.staged.collectAsState()
    val installedSet by InboxRepo.installed.collectAsState()
    val saving = item.fileId in downloading
    val savedAt = savedMap[item.fileId]
    // 助理傳來的是新版 App。這張卡就在對話裡，不必再跑一趟工具頁
    val isApk = ApkUpdate.isApk(item.name)
    val ready = isApk && item.fileId in stagedSet
    // 裝完了。安裝檔在那一刻就被清掉，所以少了這個標記，這張卡會退回
    // 「存到手機」，看起來像剛才按的那一下沒生效
    val installed = isApk && item.fileId in installedSet

    if (item.previewable) {
        LaunchedEffect(item.fileId) {
            val buf = java.io.ByteArrayOutputStream()
            client.downloadOfferedFile(item.fileId, buf)
                .onSuccess {
                    val raw = buf.toByteArray()
                    // 解碼一定要離開主執行緒。LaunchedEffect 的 coroutine 跑在
                    // Compose 的 UI dispatcher 上，decodeByteArray 是純 CPU 工作，
                    // 一張相機拍的圖就要幾百毫秒——那段時間畫面完全不動，
                    // 連捲動都停住，圖夠大就直接吃 ANR。
                    // 用 Default 不是 IO：這裡不等外部裝置，是在燒 CPU。
                    val bmp = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    }
                    preview = bmp
                    // 副檔名說是圖、實際解不出來（壞檔或不支援的格式）也算失敗，
                    // 否則轉圈會一直轉下去
                    if (bmp == null) previewFailed = true
                }
                .onFailure { previewFailed = true }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = AvatarW)
            // 提問卡有底色這張沒有，兩張同類的卡並排就差一階。補上才是同一層
            .background(Palette.Surface, Radii.Card)
            .border(1.dp, Palette.Line, Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.isImage) Icons.Filled.Image else Icons.Filled.Description,
                null, tint = Palette.Accent, modifier = Modifier.size(18.dp),
            )
            // 檔名是這張卡的主體，不是附註
            Text(
                item.name, color = Palette.Text, fontSize = Type.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(fmtBytes(item.bytes), color = Palette.TextFaint, fontSize = Type.Tiny)
        }

        if (item.note.isNotBlank()) {
            Text(item.note, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine)
        }

        preview?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                // 圖片要的是小圓角（Radii.Tiny）。這裡曾經誤用 Chip——那時 Chip 還是
                // 999dp 的膠囊，整寬的圖左右兩端會被切成半圓；現在兩檔同為 8dp，
                // 但語意上圖不是標籤，維持 Tiny
                modifier = Modifier.fillMaxWidth().clip(Radii.Tiny),
                contentScale = ContentScale.FillWidth,
            )
        }
        if (item.previewable && preview == null && !previewFailed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    Modifier.size(12.dp), color = Palette.Accent, strokeWidth = 1.5.dp,
                )
                Text("圖片載入中…", color = Palette.TextFaint, fontSize = Type.Tiny)
            }
        }
        if (previewFailed) {
            Text("圖片載不回來，原檔可能已經不在了。",
                color = Palette.TextFaint, fontSize = Type.Tiny)
        }

        Text(
            when {
                installed -> "已更新"
                ready -> "安裝這個更新"
                savedAt != null -> "已存到 $savedAt"
                saving -> if (isApk) "下載中…" else "存檔中…"
                else -> "存到手機"
            },
            color = if (installed || savedAt != null) Palette.Ok else Palette.Accent,
            fontSize = Type.Body,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                // 一行 13sp 的文字連結高度只有 18dp，遠低於 48dp 的可觸控下限。
                // 這是卡片上唯一的操作，撐成一顆看得出可以按的方塊；
                // clip 在 clickable 之前，水波紋才會跟著圓角走
                .clip(Radii.Field)
                .background(Palette.SurfaceHi)
                // 已更新的不能再按：這一版就在手機上跑著，按下去只會重抓一次
                // 一模一樣的安裝檔
                .clickable(
                    enabled = !saving && !installed && (ready || savedAt == null),
                    role = Role.Button,
                ) {
                    if (ready) {
                        InboxRepo.install(ctx, item.fileId)
                    } else {
                        // gone 這裡填 false：清單端點才算得出這個旗標，而下載
                        // 失敗本來就會走 InboxRepo 的錯誤流程，不必先問一次
                        InboxRepo.startDownload(
                            ctx, client,
                            OfferedFile(
                                id = item.fileId, name = item.name, bytes = item.bytes,
                                note = item.note, at = "", gone = false,
                            ),
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 助理問你一件事——**長在對話裡，不是彈出視窗**。
 *
 * 原本是 Dialog，但助理需要你決定的時候，人通常不在 App 裡；Dialog 是暫時性 UI，
 * 離開再回來就沒了，那則提問等於石沉大海。放進軌跡它才會一直等在那，
 * 事後也看得到自己當初選了什麼。
 *
 * 指令原文一律完整顯示不截斷——攻擊面正是「說明講 A、指令做 B」。
 */
@Composable
internal fun AskCard(
    item: TraceItem.AskItem,
    onAnswer: (String, String, String?) -> Unit,
) {
    val ask = item.req
    // 破壞性指令按下「執行」之前先要一次本人確認（伺服器用 require_biometric 指定）
    val auth = rememberDeviceAuth()
    Column(
        Modifier.fillMaxWidth().padding(start = AvatarW)
            .background(Palette.Surface, Radii.Card)
            .border(
                1.dp,
                // 還在等你的時候邊框亮起來：軌跡往下捲很快，這一列不能長得像
                // 其他過程資訊那樣可以略過
                if (item.pending) Palette.Accent.copy(alpha = 0.5f) else Palette.Line,
                Radii.Card,
            )
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            ask.title, color = Palette.Text, fontSize = Type.Body,
            fontWeight = FontWeight.Medium, lineHeight = Type.BodyLine,
        )
        if (ask.body.isNotBlank()) {
            Text(ask.body, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine)
        }
        if (ask.raw.isNotBlank()) {
            Box(
                // 程式碼區塊用 Radii.Tiny（小圓角）：等寬字要對齊左邊界，
                // 圓角一大兩行的指令就會像一顆藥丸
                Modifier.fillMaxWidth()
                    .background(Palette.Bg, Radii.Tiny)
                    .border(1.dp, Palette.Danger.copy(alpha = 0.4f), Radii.Tiny)
                    .padding(12.dp),
            ) {
                Text(
                    ask.raw, color = Palette.Text, fontSize = Type.Mono,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        if (item.pending) {
            ask.choices.sortedBy { it.id == "yes" }.forEach { c ->
                val danger = c.id == "yes" && ask.kind == "confirm_destructive"
                if (danger) {
                    TextButton(
                        onClick = {
                            auth(ask.requireBiometric, true) {
                                onAnswer(ask.askId, c.id, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(c.label, color = Palette.Danger, fontSize = Type.Body) }
                } else {
                    ActionButton(
                        c.label,
                        detail = c.detail.takeIf { it.isNotBlank() },
                        pad = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) { onAnswer(ask.askId, c.id, null) }
                }
            }
            OwnAnswerField { text -> onAnswer(ask.askId, "", text) }
        } else {
            // 空字串＝逾時或被停止，沒有人回答。這跟「選了取消」不一樣，
            // 得說清楚，否則使用者會以為是自己按的。
            val picked = ask.choices.firstOrNull { it.id == item.answeredChoiceId }
            Text(
                when {
                    item.answeredText != null -> "回了：${item.answeredText}"
                    picked != null -> "選了：${picked.label}"
                    item.answeredChoiceId.isNullOrBlank() -> "沒有回答（逾時或已停止）"
                    else -> "已處理"
                },
                color = Palette.TextFaint, fontSize = Type.Meta,
            )
        }
    }
}

/**
 * 提問卡裡的「自己回一句」。
 *
 * 選項是它猜的，未必包含你真正想講的話。伺服器端本來就收自由文字
 * （`answer.text or choice_id`），缺的一直只是入口——沒有入口，人只能改用
 * 底下的輸入列，而那時整個回合正停在這一題上，訊息只會排進佇列乾等，
 * 看起來就像 App 當掉。
 */
@Composable
private fun OwnAnswerField(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(
            text, "或者自己說",
            modifier = Modifier.weight(1f),
            fontSize = Type.Meta, lineHeight = Type.MetaLine, maxLines = 4,
            shape = Radii.Bubble,
            pad = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) { text = it }
        val can = text.isNotBlank()
        Box(
            // 這顆在提問卡片裡，38dp 的視覺尺寸不能再放大。
            // minimumInteractiveComponentSize 一定要放在 size 前面（外層），
            // 擺後面的話它收到的是固定 38dp 的限制，等於沒寫
            // 形狀跟輸入列的送出鍵同一檔（Radii.Field），不另做一顆圓的
            Modifier.minimumInteractiveComponentSize()
                .size(38.dp)
                .clip(Radii.Field)
                .background(if (can) Palette.Accent else Palette.Surface)
                .clickable(enabled = can, role = Role.Button) { onSubmit(text.trim()); text = "" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send, "送出回答",
                tint = if (can) Palette.Bg else Palette.TextFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
