package dev.butlerkit.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v1/courses` 與 `/v1/courses/{name}` 的解析（伺服器直接讀資料夾給的形狀，
 * 見 server/transport/courses_api.py）。level／basis 要**原樣**帶過來，App 端不改寫。
 */
class CoursesParseTest {

    private val list = """
        {"root": "C:/x/courses", "courses": [
          {"name": "微積分一", "conv_id": "course:微積分一", "title": "微積分（一）",
           "teacher": "林育立", "room": "M203", "slot": "週三 第1節", "note": "必修 3 學分",
           "counts": {"懂": 1, "半懂": 2, "不會": 0},
           "log_count": 5, "last_log": "09-07 10:15", "agenda_ids": ["c1", "c2"]},
          {"name": "基礎物理"}
        ]}
    """.trimIndent()

    private val detail = """
        {"name": "微積分一", "conv_id": "course:微積分一", "title": "微積分（一）",
         "counts": {"懂": 1, "半懂": 1, "不會": 1}, "agenda_ids": ["c1"],
         "info": {"老師": "林育立", "教室": "M203", "時段": "週三 第1節"},
         "progress": [
           {"topic": "極限", "level": "半懂", "basis": "提問推斷", "stuck": "ε 誰先給", "updated": "09-07"},
           {"topic": "連鎖律", "level": "懂", "basis": "答對檢核", "stuck": "", "updated": "09-07"}
         ],
         "sections": [{"title": "評分方式", "md": "| 項目 | 佔比 |\n|---|---|\n| 期中 | 40% |"}],
         "index_md": "# 微積分（一）",
         "log": [{"time": "09-07 10:02", "topic": "極限", "asked": "ε 是誰先給的", "verdict": "半懂"}],
         "log_md": "# log",
         "files": [{"path": "raw/第一週講義.pdf", "name": "第一週講義.pdf", "dir": "raw", "size": 13, "mtime": "2026-09-07T10:00:00"}]}
    """.trimIndent()

    @Test
    fun `清單解析`() {
        val l = parseCoursesList(list)!!
        assertEquals("C:/x/courses", l.root)
        val c = l.courses[0]
        assertEquals("微積分（一）", c.title)
        assertEquals("course:微積分一", c.convId)
        assertEquals("林育立", c.teacher)
        assertEquals(1, c.count("懂"))
        assertEquals(2, c.count("半懂"))
        assertEquals(0, c.count("不會"))
        assertEquals(listOf("c1", "c2"), c.agendaIds)
        assertEquals(5, c.logCount)
        assertEquals("09-07 10:15", c.lastLog)
    }

    @Test
    fun `缺欄位不炸_標題退回名字`() {
        val p = parseCoursesList(list)!!.courses[1]
        assertEquals("基礎物理", p.title)
        assertEquals("", p.teacher)
        assertEquals(0, p.count("懂"))
        assertTrue(p.agendaIds.isEmpty())
        assertEquals(0, p.logCount)
    }

    @Test
    fun `詳情解析`() {
        val d = parseCourseDetail(detail)!!
        assertEquals("微積分（一）", d.info.title)
        assertEquals("林育立", d.fields["老師"])
        assertEquals(2, d.progress.size)
        assertEquals("半懂", d.progress[0].level)
        assertEquals("提問推斷", d.progress[0].basis)     // 原樣，不改寫
        assertEquals("ε 誰先給", d.progress[0].stuck)
        assertEquals("答對檢核", d.progress[1].basis)
        assertEquals("評分方式", d.sections.single().title)
        assertTrue(d.sections.single().md.contains("| 期中 | 40% |"))
        assertEquals("# 微積分（一）", d.indexMd)
        val log = d.log.single()
        assertEquals("09-07 10:02", log.time)
        assertEquals("ε 是誰先給的", log.asked)
        val f = d.files.single()
        assertEquals("raw/第一週講義.pdf", f.path)
        assertEquals("raw", f.dir)
        assertEquals(13L, f.size)
    }

    @Test
    fun `課程資訊欄位順序固定_其餘排後`() {
        val ordered = orderedFields(mapOf("備註" to "b", "其他" to "o", "老師" to "t", "教室" to "r"))
        assertEquals(
            listOf("老師" to "t", "教室" to "r", "備註" to "b", "其他" to "o"),
            ordered,
        )
    }

    @Test
    fun `壞 JSON 回 null 不拋`() {
        assertNull(parseCoursesList("{這不是"))
        assertNull(parseCoursesList(""))
        assertNull(parseCourseDetail(""))
    }

    @Test
    fun `空殼用名字湊_對話 id 帶前綴`() {
        val b = CourseInfo.bare("微積分一")
        assertEquals("course:微積分一", b.convId)
        assertEquals("微積分一", b.title)
        assertEquals(0, b.count("懂"))
    }
}
