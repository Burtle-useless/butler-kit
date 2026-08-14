package dev.butlerkit.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.SearchHit
import dev.butlerkit.app.voice.TalkActivity
import kotlinx.coroutines.launch

/**
 * 工具頁：cc-bot 搬過來的「電腦端能力」，跟聊天完全分開。
 * Phase 3 先有截圖；之後的工具照同樣的區塊模式往下加。
 */
@Composable
fun ToolsScreen(client: ButlerClient, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 設定的入口在這裡：它已經不在底欄了（見 MainActivity 的 Tab），
        // 而工具頁是「低頻但要找得到」的東西的家，設定正是這種。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工具", color = Palette.Text, fontSize = Type.Title,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings, "設定",
                    tint = Palette.TextDim, modifier = Modifier.size(22.dp),
                )
            }
        }

        // 收件匣放最上面：這是唯一「助理主動塞東西進來」的區塊，
        // 有新檔案時它得是打開這頁第一眼看到的東西。
        InboxCard(client)

        // ── 面對面翻譯 ──────────────────────────────────────────────
        // 這一區跟這頁其他工具性質不同：它完全在手機上跑，電腦關機也能用。
        // 放在這裡是因為沒有更好的地方——底部分頁五個已經滿了。
        val context = LocalContext.current
        Column(
            Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                .padding(Space.Inner),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("面對面翻譯", color = Palette.Text, fontSize = Type.Body)
            Text(
                "底下兩顆按鈕，誰要講就按自己那顆，譯文出現在同一面兩個人一起看，" +
                    "手機不用轉來轉去。收音孔在底部，講的人把底端朝自己會清楚一點。" +
                    "翻譯走 Google，離線語言包只有斷網時才會用到，平常不必管它。",
                color = Palette.TextDim, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
            Button(
                onClick = {
                    context.startActivity(Intent(context, TalkActivity::class.java))
                },
                shape = Radii.Chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent, contentColor = Palette.Bg,
                ),
            ) { Text("開始對話", fontSize = Type.Meta) }
        }

        // ── 看電腦畫面 ──────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                .padding(Space.Inner),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("電腦畫面", color = Palette.Text, fontSize = Type.Body)
            Text(
                "抓當下的完整桌面（含雙螢幕）。",
                color = Palette.TextDim, fontSize = Type.Meta,
            )
            Button(
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        client.screenshot()
                            .onSuccess { bytes ->
                                shot = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            .onFailure { error = it.message }
                        loading = false
                    }
                },
                enabled = !loading,
                shape = Radii.Chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent, contentColor = Palette.Bg,
                ),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), color = Palette.Bg, strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (shot == null) "截一張" else "再截一張", fontSize = Type.Meta)
                }
            }
            error?.let { Text(it, color = Palette.Danger, fontSize = Type.Meta) }
            shot?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "電腦畫面",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        // ── 對話搜尋 ────────────────────────────────────────────────
        var query by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
        var searched by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                .padding(Space.Inner),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("對話搜尋", color = Palette.Text, fontSize = Type.Body)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f)
                        .background(Palette.SurfaceHi, Radii.Chip)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = Type.Meta, color = Palette.Text,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("關鍵字", color = Palette.TextFaint,
                                    fontSize = Type.Meta)
                            }
                            inner()
                        }
                    },
                )
                Button(
                    onClick = {
                        scope.launch {
                            client.search(query).onSuccess {
                                hits = it; searched = true
                            }
                        }
                    },
                    enabled = query.isNotBlank(),
                    shape = Radii.Chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Accent, contentColor = Palette.Bg,
                    ),
                ) { Text("找", fontSize = Type.Meta) }
            }
            if (searched && hits.isEmpty()) {
                Text("什麼都沒找到。", color = Palette.TextFaint, fontSize = Type.Meta)
            }
            hits.forEach { h ->
                Column(
                    Modifier.fillMaxWidth().background(Palette.SurfaceHi, Radii.Chip)
                        .padding(10.dp),
                ) {
                    Text(h.title, color = Palette.Accent, fontSize = Type.Tiny)
                    Text(h.snippet, color = Palette.TextDim, fontSize = Type.Meta,
                        lineHeight = Type.MetaLine)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                .padding(Space.Inner),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("之後會出現在這裡", color = Palette.TextDim, fontSize = Type.Meta)
            Text(
                "想到什麼跟助理說。",
                color = Palette.TextFaint, fontSize = Type.Tiny,
            )
        }
    }
}
