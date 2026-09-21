package dev.butlerkit.app.ui

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.data.CoursesRepo
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.CourseDetail as CourseDetailData
import dev.butlerkit.app.net.CourseFile
import dev.butlerkit.app.net.CourseInfo
import dev.butlerkit.app.net.Period
import dev.butlerkit.app.net.orderedFields
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/**
 * 課程頁：一門課一個工作區。
 *
 * 第一層是課表（從日常頁搬來，[CoursePane]）加「現在這堂」與課程清單——課表是最
 * 直覺的選課介面，格子點下去就進那門課；沒有課程資料夾的（體育）只開課表資訊。
 * 第二層是一門課的工作區：對話／紀錄／規範／程度／檔案五個區。對話是那門課整學期
 * 的一條（`course:` 加資料夾名，見 [ChatViewModel.courseConv]），其餘四區直接讀電腦上
 * 那門課資料夾裡的東西（見 `net/Courses.kt`）。
 *
 * 進了某門課才切對話；回到第一層不切回——第一層沒有畫面在看任何對話，切回主對話
 * 只會多拉一次 snapshot。切去別的分頁再回來，[picked] 由 SaveableStateProvider 保住，
 * `LaunchedEffect(picked)` 重跑會再把對話切回來。
 */
@Composable
fun CourseScreen(
    state: ChatState,
    vm: ChatViewModel,
    /** 從通知點進來要直接落在哪門課（資料夾名）。進去之後叫 [onPendingConsumed] 清掉。 */
    pendingCourse: String?,
    onPendingConsumed: () -> Unit,
) {
    val ctx = LocalContext.current
    val agenda by AgendaRepo.data.collectAsState()
    val list by CoursesRepo.list.collectAsState()
    val coursesError by CoursesRepo.error.collectAsState()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingCourse) {
        if (pendingCourse != null) {
            picked = pendingCourse
            onPendingConsumed()
        }
    }
    // 每次進這頁都重拉清單：提問數與程度計數上課時幾分鐘就變一次
    LaunchedEffect(Unit) { CoursesRepo.refresh(ctx, vm.client) }
    LaunchedEffect(picked) { picked?.let { vm.enterCourse(ChatViewModel.courseConv(it)) } }
    BackHandler(enabled = picked != null) { picked = null }

    val infos = list?.courses
    val p = picked
    if (p == null) {
        CourseHome(vm, agenda.courses, agenda.periods, infos, coursesError) { picked = it }
    } else {
        // 清單還沒到（離線、或從通知直接進來）就先用名字湊一個空殼，對話照樣能開
        val info = infos?.firstOrNull { it.name == p } ?: CourseInfo.bare(p)
        CourseWorkspace(state, vm, info) { picked = null }
    }
}

// ── 第一層：現在這堂＋課表＋清單 ────────────────────────────────────────────
@Composable
private fun CourseHome(
    vm: ChatViewModel,
    courses: List<Course>,
    periods: List<Period>,
    infos: List<CourseInfo>?,
    error: String?,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val resolve: (Course) -> CourseInfo? = { c -> infos?.firstOrNull { c.id in it.agendaIds } }

    Column(Modifier.fillMaxSize()) {
        PageTitle("課程") {
            Text(
                "${today.substring(5, 7).trimStart('0')} 月 " +
                    "${today.substring(8, 10).trimStart('0')} 日" +
                    " 週${WEEK[todayDay()]}",
                color = Palette.TextFaint, fontSize = Type.Meta,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        CoursePane(
            ctx, scope, vm.client, courses, periods, today,
            resolve = resolve,
            onOpenCourse = { onOpen(it.name) },
            lead = {
                item { NowCard(courses, periods, resolve) { onOpen(it.name) } }
            },
            extra = {
                item {
                    Card {
                        SectionHead(
                            "課程",
                            hint = infos?.let { "${it.size} 門" },
                            tint = Accents.Course,
                        )
                        CourseList(infos, error) { onOpen(it.name) }
                    }
                }
            },
        )
    }
}

/**
 * 「現在這堂」：課表最常被問的那一句，放最上面。點下去直接進正在上（或下一堂）
 * 那門課的工作區——上課中掏出手機要問問題，這一步要最短。今天沒課就不畫。
 */
@Composable
private fun NowCard(
    courses: List<Course>,
    periods: List<Period>,
    resolve: (Course) -> CourseInfo?,
    onOpen: (CourseInfo) -> Unit,
) {
    val todays = courses.filter { it.day == todayDay() }.sortedBy { it.fromPeriod }
    if (todays.isEmpty()) return
    val cal = Calendar.getInstance()
    val now = nowSlot(periods, todays, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    val target = (now.current ?: now.next)?.let(resolve)
    Box(
        Modifier.fillMaxWidth().clip(Radii.Card)
            .let { m -> if (target != null) m.clickable(role = Role.Button) { onOpen(target) } else m },
    ) {
        Card(pad = 10.dp) {
            NowLine(now)
            if (target != null) {
                Text(
                    "點一下進「${target.title}」", color = Palette.TextFaint, fontSize = Type.Tiny,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                )
            }
        }
    }
}

// ── 第二層：一門課的工作區 ──────────────────────────────────────────────────
private enum class Ws(val label: String) {
    Chat("對話"), Log("紀錄"), Rules("規範"), Progress("程度"), Files("檔案"),
}

@Composable
private fun CourseWorkspace(
    state: ChatState,
    vm: ChatViewModel,
    info: CourseInfo,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Ws.Chat) }
    val details by CoursesRepo.details.collectAsState()
    val errors by CoursesRepo.detailError.collectAsState()
    val name = info.name
    val data = details[name]

    // 進來拉一次；之後每次切到對話以外的區再拉——紀錄與程度上課時每幾分鐘變一次，
    // 而對話那區不需要它
    LaunchedEffect(name, tab) {
        if (tab != Ws.Chat || data == null) CoursesRepo.refreshDetail(vm.client, name)
    }

    val sub = listOf(info.teacher, info.room).filter { it.isNotBlank() }.joinToString("・")

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        WsHeader(info.title, sub, state.connError, onBack)
        PillTabs(Ws.entries, tab, { it.label }, Accents.Course) { tab = it }
        HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
        when (tab) {
            // 聊天畫面自己的報頭關掉：課名與返回鍵在上面那列，再疊一行標題是兩層報頭
            Ws.Chat -> ChatScreen(
                state = state,
                title = info.title,
                multiConv = false,
                showTopBar = false,
                client = vm.client,
                onSend = vm::send,
                onDraft = vm::setDraft,
                onStop = vm::stop,
                onStopBg = vm::stopBgTask,
                onAnswer = vm::answerAsk,
                onSwitchConv = vm::switchCcConversation,
                onNewConv = vm::newConversation,
                onDeleteConv = vm::deleteConversation,
                onAttach = vm::attach,
                onRemoveAttach = vm::removeAttachment,
                onConvSettings = vm::applyConvSettings,
                onLoadOlder = { vm.loadOlder(state.currentConv) },
            )
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(Space.Screen),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errors[name]?.let {
                    Text(
                        "拿不到這門課的資料：$it", color = Palette.Danger, fontSize = Type.Meta,
                        modifier = Modifier.fillMaxWidth().background(Palette.DangerSoft)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (data == null) {
                    if (errors[name] == null) {
                        Text("讀取中…", color = Palette.TextFaint, fontSize = Type.Meta)
                    }
                } else when (tab) {
                    Ws.Log -> LogPane(data)
                    Ws.Rules -> RulesPane(data)
                    Ws.Progress -> ProgressPane(data)
                    Ws.Files -> FilesPane(data) { f ->
                        scope.launch {
                            CoursesRepo.fetchFile(ctx, vm.client, name, f)
                                .onSuccess { openFile(ctx, it) }
                                .onFailure {
                                    Toast.makeText(
                                        ctx, "抓不到 ${f.name}：${it.message ?: "下載失敗"}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        }
                    }
                    Ws.Chat -> Unit
                }
            }
        }
    }
}

/** 工作區的報頭：返回鍵、課名、老師・教室；斷線時右邊印一句紅字（同聊天頁的做法）。 */
@Composable
private fun WsHeader(title: String, sub: String, connError: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(end = Space.Screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "回課表", tint = Palette.TextDim)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = Type.Head, fontWeight = FontWeight.Bold,
                color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Text(sub, color = Palette.TextDim, fontSize = Type.Tiny, maxLines = 1)
            }
        }
        connError?.let {
            Text(
                it, fontSize = Type.Tiny, color = Palette.Danger,
                modifier = Modifier.padding(start = 8.dp), maxLines = 1,
            )
        }
    }
}

/**
 * 一排膠囊分頁。選中的實色、沒選中的只有一圈邊框——幾顆一起亮著看不出哪顆是現在這頁。
 * 日常頁與課程工作區共用；泛型是因為兩邊的分頁各自是 private enum。
 */
@Composable
internal fun <T> PillTabs(
    items: List<T>,
    current: T,
    label: (T) -> String,
    tint: Color,
    onPick: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { s ->
            val on = s == current
            Text(
                label(s),
                color = if (on) Palette.Bg else Palette.TextDim,
                fontSize = Type.Body,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (on) tint else Color.Transparent, Radii.Chip)
                    .border(
                        if (on) 0.dp else 1.dp,
                        if (on) Color.Transparent else Palette.Line,
                        Radii.Chip,
                    )
                    .clickable(role = Role.RadioButton) { onPick(s) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

// ── 四個唯讀區 ──────────────────────────────────────────────────────────────
@Composable
private fun LogPane(d: CourseDetailData) {
    Card {
        SectionHead(
            "提問紀錄", hint = if (d.log.isEmpty()) null else "${d.log.size} 則",
            tint = Accents.Course,
        )
        LogList(d.log)
    }
}

@Composable
private fun RulesPane(d: CourseDetailData) {
    val fields = orderedFields(d.fields)
    if (fields.isNotEmpty()) {
        Card {
            SectionHead("課程資訊", tint = Accents.Course)
            fields.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(k, color = Palette.TextDim, fontSize = Type.Meta, modifier = Modifier.width(56.dp))
                    Text(v, color = Palette.Text, fontSize = Type.Meta, lineHeight = Type.MetaLine)
                }
            }
        }
    }
    // index.md 裡「理解進度」以外的每一段（評分方式、教學進度、待辦…）原文照排
    d.sections.forEach { s ->
        Card {
            SectionHead(s.title, tint = Accents.Course)
            if (s.md.isBlank()) {
                Text("（還沒寫）", color = Palette.TextFaint, fontSize = Type.Meta)
            } else {
                MarkdownText(s.md)
            }
        }
    }
    if (fields.isEmpty() && d.sections.isEmpty()) {
        Text(
            "這門課的 index.md 還沒有內容。把課程大綱拍上來，讓它幫你填進去。",
            color = Palette.TextFaint, fontSize = Type.Meta, lineHeight = Type.MetaLine,
        )
    }
}

@Composable
private fun ProgressPane(d: CourseDetailData) {
    Card {
        SectionHead("理解程度", tint = Accents.Course)
        CountsRow(d.info)
        Text(
            "程度是從他問過什麼推的，不是考出來的；依據欄寫「答對檢核」的才是真的驗過。",
            color = Palette.TextFaint, fontSize = Type.Tiny,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        ProgressTable(d.progress)
    }
}

private val DIR_LABEL = mapOf("raw" to "教材", "notes" to "筆記", "對話" to "對話原文")

@Composable
private fun FilesPane(d: CourseDetailData, onOpen: (CourseFile) -> Unit) {
    if (d.files.isEmpty()) {
        Card {
            SectionHead("檔案", tint = Accents.Course)
            Text(
                "還沒有教材。講義、投影片傳上來就會放進這門課的資料夾；" +
                    "從手機直接上傳到這裡是下一階段的事。",
                color = Palette.TextFaint, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
        }
        return
    }
    DIR_LABEL.forEach { (dir, label) ->
        val files = d.files.filter { it.dir == dir }
        if (files.isEmpty()) return@forEach
        Card {
            SectionHead(label, hint = "${files.size} 個", tint = Accents.Course)
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(f) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.name, color = Palette.Text, fontSize = Type.Body,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        // 子資料夾（raw/第三週/xxx.jpg）只印中間那段，檔名已經在上面
                        val mid = f.path.removePrefix("$dir/").removeSuffix(f.name).trimEnd('/')
                        Text(
                            listOf(mid, fmtSize(f.size), f.mtime.take(16).replace("T", " "))
                                .filter { it.isNotBlank() }.joinToString("　"),
                            color = Palette.TextFaint, fontSize = Type.Tiny,
                        )
                    }
                }
            }
        }
    }
}

private fun fmtSize(b: Long): String = when {
    b >= 1_000_000 -> String.format("%.1f MB", b / 1e6)
    b >= 1_000 -> "${b / 1000} KB"
    else -> "$b B"
}

/**
 * 抓下來的教材交給系統開。一定要走 FileProvider 給 content:// URI（同 ApkUpdate），
 * 白名單在 res/xml/file_paths.xml 的 `courses/` 那條。
 */
private fun openFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(i, f.name)) }
        .onFailure { Toast.makeText(ctx, "沒有 App 能開這個檔", Toast.LENGTH_SHORT).show() }
}
