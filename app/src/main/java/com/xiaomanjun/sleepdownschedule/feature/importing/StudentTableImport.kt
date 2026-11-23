package com.xiaomanjun.sleepdownschedule.feature.importing

import com.xiaomanjun.sleepdownschedule.ImportDraft
import com.xiaomanjun.sleepdownschedule.PeriodEntity
import com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity
import com.xiaomanjun.sleepdownschedule.defaultPeriods
import java.time.LocalTime

/**
 * 教务系统导出的“学生课表”HTML 表格解析器（扩展名通常是 .xls，本质是 HTML）。
 *
 * 参考 class-schedule 的 parseStudentTable：行是节次、列是星期，每个单元格内按
 * “课程(代码) / (教师) / (周次 地点)”重复。与位图格式不同，这里的周次就是真实周号，
 * 支持 1-3单、4-5、7-13 这类写法。
 */
class StudentTableImportException(message: String) : Exception(message)

object StudentTableParser {
    private val tableRegex = Regex(
        "<table\\b[^>]*\\bid\\s*=\\s*[\"']?manualArrangeCourseTable[\\s\\S]*?</table>",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val rowRegex = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val cellRegex = Regex("<(td|th)\\b[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
    private val brRegex = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^>]+>")
    private val entityRegex = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val courseLineRegex = Regex("^(.*?)\\s*\\(([0-9A-Za-z._-]+)\\)\\s*$")
    private val parenthesizedRegex = Regex("^\\((.*)\\)$")
    private val weekAndLocationRegex = Regex("^([0-9][0-9,\\-单双]*)\\s*(.*)$")
    private val periodLabelRegex = Regex("^第?([一二三四五六七八九十百零\\d]+)节?$")
    private val weekPartRegex = Regex("^(\\d+)(?:-(\\d+))?(单|双)?$")
    private val spacesRegex = Regex("[ \\t]+")
    private val whitespaceRegex = Regex("\\s+")

    private val chineseDigits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    fun looksLikeStudentTable(text: String): Boolean =
        tableRegex.containsMatchIn(text) ||
            ("学生课表" in text && "<table" in text.lowercase() && "星期一" in text)

    fun parse(
        text: String,
        baseConfig: ScheduleConfigEntity,
        basePeriods: List<PeriodEntity> = emptyList(),
        fileName: String? = null,
    ): ImportDraft {
        val source = text.replace("\r\n", "\n").replace('\r', '\n')
        val table = tableRegex.find(source)?.value
            ?: if (looksLikeStudentTable(source)) source else
                throw StudentTableImportException(
                    "这不是教务系统导出的学生课表文件：未找到课表表格（manualArrangeCourseTable）。"
                )

        val periods = mutableListOf<Int>()
        val lessons = mutableListOf<Lesson>()
        val warnings = mutableListOf<String>()

        for (row in rowRegex.findAll(table)) {
            val cells = cellRegex.findAll(row.groupValues[1]).toList()
            if (cells.size != 8) continue
            val period = periodFromLabel(cellText(cells[0].groupValues[2]).replace(" ", "")) ?: continue
            periods += period
            for (weekday in 1..7) {
                val lines = cellLines(cells[weekday].groupValues[2])
                if (lines.isEmpty()) continue
                parseCell(lines, weekday, period, lessons, warnings)
            }
        }

        val periodCount = periods.maxOrNull() ?: 0
        if (periodCount == 0) {
            throw StudentTableImportException("课表表格里没有识别到“第一节”等节次行。")
        }
        if (lessons.isEmpty()) {
            throw StudentTableImportException("课表里没有解析到任何课程。")
        }

        val merged = mergeLessons(lessons)
        val maxWeek = merged.flatMap { it.weeks }.maxOrNull() ?: 0
        if (maxWeek == 0) {
            throw StudentTableImportException("课程缺少有效周次，无法导入。")
        }

        val payloadPeriods = buildPeriods(periodCount, basePeriods, baseConfig)
        val payload = ScheduleImportPayload(
            schemaVersion = 1,
            scheduleConfig = ScheduleConfigPayload(totalWeeks = maxWeek, periods = payloadPeriods),
            courses = merged.map { lesson ->
                ScheduleImportCourse(
                    name = lesson.name,
                    teacher = lesson.teacher.ifBlank { null },
                    location = lesson.location.ifBlank { null },
                    weekday = lesson.weekday,
                    periods = lesson.periods,
                    weeks = lesson.weeks,
                    note = lesson.note.ifBlank { null },
                )
            }
        )

        val draft = ScheduleImportParser.buildDraftFromPayload(payload, baseConfig)
        val notices = buildList {
            if (!fileName.isNullOrBlank()) add("已解析《$fileName》")
            add("${merged.size} 门课程 · ${maxWeek} 周 · $periodCount 个节次")
            addAll(warnings.distinct().take(SUPPORTED_NOTICE_LIMIT))
        }
        return draft.copy(notices = notices)
    }

    private data class Lesson(
        val weekday: Int,
        val period: Int,
        val name: String,
        val teacher: String,
        val location: String,
        val code: String,
        val weeks: List<Int>,
        val note: String,
    )

    private fun parseCell(
        lines: List<String>,
        weekday: Int,
        period: Int,
        out: MutableList<Lesson>,
        warnings: MutableList<String>,
    ) {
        val entries = mutableListOf<ParsedEntry>()
        var current: MutableList<String>? = null
        for (line in lines) {
            val course = courseLineRegex.matchEntire(line)
            if (course != null && course.groupValues[1].trim().isNotEmpty()) {
                current?.let { entries += parseEntry(it) }
                current = mutableListOf(course.groupValues[1].trim(), course.groupValues[2])
            } else if (current != null) {
                current.add(line)
            }
        }
        current?.let { entries += parseEntry(it) }

        for (entry in entries) {
            val name = entry.name.ifBlank { "未命名课程" }
            var teacher = ""
            var wloc = ""
            val notes = mutableListOf<String>()
            for (item in entry.rest) {
                val parenthesized = parenthesizedRegex.matchEntire(item)?.groupValues?.get(1)?.trim()
                if (parenthesized != null) {
                    when {
                        teacher.isEmpty() -> teacher = parenthesized
                        wloc.isEmpty() -> wloc = parenthesized
                        else -> notes += parenthesized
                    }
                } else {
                    notes += item
                }
            }

            var location = ""
            var weeks: List<Int> = emptyList()
            val weekMatch = weekAndLocationRegex.matchEntire(wloc)
            if (weekMatch != null) {
                val parsed = parseWeekList(weekMatch.groupValues[1])
                weeks = parsed.weeks
                location = weekMatch.groupValues[2].trim()
                if (parsed.invalid.isNotEmpty()) {
                    warnings += "“$name”的周次写法无法完全解析：${parsed.invalid.joinToString(", ")}"
                }
            } else if (wloc.isNotBlank()) {
                warnings += "“$name”的周次/地点缺少周次前缀：$wloc"
                location = wloc
            }

            if (weeks.isEmpty()) {
                warnings += "“$name”没有可用周次，未导入。"
                continue
            }
            out += Lesson(
                weekday = weekday,
                period = period,
                name = name,
                teacher = teacher,
                location = location,
                code = entry.code,
                weeks = weeks,
                note = notes.joinToString(" "),
            )
        }
    }

    private data class ParsedEntry(val name: String, val code: String, val rest: List<String>)

    private fun parseEntry(values: List<String>): ParsedEntry =
        ParsedEntry(
            name = values.getOrElse(0) { "" },
            code = values.getOrElse(1) { "" },
            rest = values.drop(2),
        )

    /** 同一天、同课程、同教师/教室/代码/周次的连续节次合并为一个课程块。 */
    private fun mergeLessons(lessons: List<Lesson>): List<MergedLesson> {
        val groups = LinkedHashMap<String, MutableList<Lesson>>()
        for (lesson in lessons) {
            val key = listOf(
                lesson.weekday, lesson.name, lesson.teacher, lesson.location, lesson.code,
                lesson.weeks.joinToString(",")
            ).joinToString("\u0000")
            groups.getOrPut(key) { mutableListOf() } += lesson
        }

        val merged = mutableListOf<MergedLesson>()
        for (group in groups.values) {
            group.sortBy { it.period }
            var start = group.first().period
            var end = start
            var note = group.first().note
            for (lesson in group.drop(1)) {
                if (lesson.period <= end + 1) {
                    end = maxOf(end, lesson.period)
                    if (note.isBlank()) note = lesson.note
                } else {
                    merged += group.first().toMerged(start, end, note)
                    start = lesson.period
                    end = lesson.period
                    note = lesson.note
                }
            }
            merged += group.first().toMerged(start, end, note)
        }
        return merged.sortedWith(
            compareBy<MergedLesson> { it.weekday }.thenBy { it.periods.first() }.thenBy { it.name }
        )
    }

    private data class MergedLesson(
        val weekday: Int,
        val name: String,
        val teacher: String,
        val location: String,
        val code: String,
        val weeks: List<Int>,
        val note: String,
        val periods: List<Int>,
    )

    private fun Lesson.toMerged(start: Int, end: Int, note: String) = MergedLesson(
        weekday = weekday,
        name = name,
        teacher = teacher,
        location = location,
        code = code,
        weeks = weeks,
        note = note,
        periods = (start..end).toList(),
    )

    private fun buildPeriods(
        periodCount: Int,
        basePeriods: List<PeriodEntity>,
        baseConfig: ScheduleConfigEntity,
    ): List<PeriodPayload> {
        val source = basePeriods.ifEmpty { defaultPeriods(baseConfig.id) }
            .filter { it.periodIndex > 0 }
            .distinctBy { it.periodIndex }
            .sortedBy { it.periodIndex }
            .associateBy { it.periodIndex }
        val fallback = source.values.maxByOrNull { it.periodIndex }
        val classMinutes = baseConfig.classDurationMinutes.coerceIn(1, 180)
        val breakMinutes = baseConfig.breakDurationMinutes.coerceIn(0, 60)
        var cursor = fallback?.endTime?.let(::parseTimeOrNull)

        return (1..periodCount).map { index ->
            val existing = source[index]
            if (existing != null) {
                cursor = parseTimeOrNull(existing.endTime)
                PeriodPayload(index, existing.startTime, existing.endTime)
            } else {
                val start = cursor?.plusMinutes(breakMinutes.toLong()) ?: LocalTime.of(8, 0)
                val end = start.plusMinutes(classMinutes.toLong())
                cursor = end
                PeriodPayload(index, formatTime(start), formatTime(end))
            }
        }
    }

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    private fun parseTimeOrNull(value: String): LocalTime? =
        runCatching { LocalTime.parse(value) }.getOrNull()

    private fun cellLines(html: String): List<String> {
        val withBreaks = brRegex.replace(html, "\n")
        val text = htmlDecode(tagRegex.replace(withBreaks, " ")).replace('\u00a0', ' ')
        return text.split('\n')
            .map { it.replace(spacesRegex, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun cellText(html: String): String =
        htmlDecode(tagRegex.replace(brRegex.replace(html, " "), " ")).replace('\u00a0', ' ').trim()

    private fun htmlDecode(text: String): String = entityRegex.replace(text) { match ->
        val code = match.groupValues[1]
        if (code.startsWith("#")) {
            val hex = code.length > 1 && (code[1] == 'x' || code[1] == 'X')
            val value = code.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)
            value?.let { String(Character.toChars(it)) } ?: match.value
        } else {
            when (code.lowercase()) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> match.value
            }
        }
    }

    private fun periodFromLabel(label: String): Int? {
        if (label.isBlank() || "虚拟" in label) return null
        val match = periodLabelRegex.matchEntire(label) ?: return null
        val number = chineseNumber(match.groupValues[1]) ?: return null
        return number.takeIf { it in 1..64 }
    }

    private fun chineseNumber(value: String): Int? {
        if (value.isEmpty()) return null
        if (value.all { it.isDigit() }) return value.toIntOrNull()
        if (value == "十") return 10
        if ("十" in value) {
            val parts = value.split("十")
            val tens = if (parts[0].isEmpty()) 1 else chineseDigits[parts[0].first()]
            val ones = if (parts.size < 2 || parts[1].isEmpty()) 0 else chineseDigits[parts[1].first()]
            if (tens == null || ones == null) return null
            return tens * 10 + ones
        }
        return chineseDigits[value.first()]
    }

    private data class WeekList(val weeks: List<Int>, val invalid: List<String>)

    /** "1-3单,4-5,7-13" → [1,3,4,5,7..13] */
    private fun parseWeekList(spec: String): WeekList {
        val normalized = spec
            .replace('，', ',').replace('、', ',')
            .replace("—", "-").replace("–", "-").replace('－', '-')
            .replace("~", "-").replace("～", "-")
            .replace("周", "")
            .replace(whitespaceRegex, "")
        val weeks = sortedSetOf<Int>()
        val invalid = mutableListOf<String>()
        for (part in normalized.split(',')) {
            if (part.isEmpty()) continue
            val match = weekPartRegex.matchEntire(part)
            if (match == null) {
                invalid += part
                continue
            }
            val start = match.groupValues[1].toIntOrNull() ?: continue
            val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull() ?: continue
            if (start < 1 || end < start) {
                invalid += part
                continue
            }
            for (week in start..minOf(end, MAX_STUDENT_TABLE_WEEK)) {
                when (match.groupValues[3]) {
                    "单" -> if (week % 2 == 1) weeks += week
                    "双" -> if (week % 2 == 0) weeks += week
                    else -> weeks += week
                }
            }
        }
        return WeekList(weeks.toList(), invalid)
    }

    private const val MAX_STUDENT_TABLE_WEEK = 60
    private const val SUPPORTED_NOTICE_LIMIT = 6
}
