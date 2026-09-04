package dev.butlerkit.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.LedgerEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// ── 記帳 ─────────────────────────────────────────────────────────────────────
@Composable
internal fun MoneyPane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient,
    ledger: List<LedgerEntry>, categories: List<String>,
) {
    var amount by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("餐飲") }
    var note by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }

    val month = remember(ledger) { thisMonth() }
    val rows = ledger.filter { it.ts.startsWith(month) }
    val spent = rows.filter { !it.income }.sumOf { it.amount }
    val got = rows.filter { it.income }.sumOf { it.amount }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // 月結直接在本地算：明細本來就整批拉下來了，為了一個加總再打一次 API
            // 只會讓數字跟清單短暫對不起來
            Card {
                Text("這個月花了", color = Palette.TextDim, fontSize = Type.Meta)
                Text(
                    fmtMoney(spent),
                    color = Palette.Text, fontSize = Type.Metric,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    // 收入與結餘拆成兩塊：擠在一行時中間那個全形空白撐不出分界，
                    // 兩個數字會讀成一個
                    Text(
                        "收入 ${fmtMoney(got)}",
                        color = Palette.TextDim, fontSize = Type.Meta,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                    Text(
                        "結餘 ${fmtMoney(got - spent)}",
                        color = if (got - spent >= 0) Accents.Money else Palette.Danger,
                        fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item {
            AddPanel(
                label = "記一筆",
                tint = Accents.Money,
                canSubmit = amount.toDoubleOrNull() != null,
                submitText = "記一筆",
                onSubmit = {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "ledger", JSONObject().apply {
                            put("amount", amount.toDouble())
                            put("category", cat)
                            put("note", note.trim())
                            put("income", income)
                        })
                        if (ok) { amount = ""; note = "" }
                    }
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field(amount, "金額", KeyboardType.Number) { s ->
                            amount = s.filter { it.isDigit() || it == '.' }
                        }
                    }
                    Text(
                        if (income) "收入" else "支出",
                        color = if (income) Palette.Bg else Palette.Text,
                        fontSize = Type.Body,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                if (income) Accents.Money else Palette.SurfaceHi, Radii.Field,
                            )
                            .clickable(role = Role.Button) { income = !income }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                // 分類清單由伺服器給：固定清單才加總得起來，自由輸入會讓
                // 「餐飲」「吃飯」「伙食」變成三個分類
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { c ->
                        val on = c == cat
                        Text(
                            c,
                            color = if (on) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Meta,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .background(
                                    if (on) Accents.Money else Palette.SurfaceHi, Radii.Chip,
                                )
                                .clickable(role = Role.RadioButton) { cat = c }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Field(note, "買了什麼（可留白）") { note = it }
            }
        }
        item {
            // 標題說「本月」而且真的只列本月。原本 hint 算的是 rows（本月）、
            // 底下卻 items(ledger)（全部歷史），於是「明細 3 筆」下面躺著幾十筆，
            // 跟上面那張月結卡的金額也對不起來——三個數字互相矛盾。
            // 這一頁沒有月份切換器（month 是寫死的 thisMonth），所以「列全部歷史」
            // 本來就不是誰設計過的行為，只是漏了 filter。要翻舊帳跟助理講就好。
            SectionHead(
                "本月明細",
                hint = rows.size.takeIf { it > 0 }?.let { "$it 筆" },
                tint = Accents.Money,
            )
        }
        if (rows.isEmpty()) {
            item {
                Empty(
                    if (ledger.isEmpty()) "還沒記過帳。跟助理說「午餐 120」它會直接記進來。"
                    else "這個月還沒記過帳。",
                )
            }
        }
        items(rows, key = { it.id }) { r ->
            StripeRow(
                // 收入用綠、支出維持分頁色：這一頁唯一需要一眼分辨的就是錢的方向
                tint = if (r.income) Palette.Ok else Accents.Money,
                leadWidth = 0.dp,
                lead = {},
                actions = {
                    Text(
                        (if (r.income) "+" else "−") + fmtMoney(r.amount),
                        color = if (r.income) Palette.Ok else Palette.Text,
                        fontSize = Type.Head, fontWeight = FontWeight.Bold,
                    )
                    IconBtn(Icons.Filled.Close, "刪掉這筆") {
                        scope.launch { AgendaRepo.remove(ctx, client, "ledger", r.id) }
                    }
                },
            ) {
                Text(
                    "${r.category}${if (r.note.isBlank()) "" else "・" + r.note}",
                    color = Palette.Text, fontSize = Type.Body,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    fmtStamp(r.ts), color = Palette.TextFaint, fontSize = Type.Meta,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
