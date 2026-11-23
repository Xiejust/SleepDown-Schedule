package com.xiaomanjun.sleepdownschedule.domain.schedule

import com.xiaomanjun.sleepdownschedule.model.*

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTermBoundaryTest {
    private val config = defaultConfig().copy(
        autoCurrentWeek = true,
        termStartDate = "2026-09-02",
        totalWeeks = 2
    )

    @Test
    fun shorteningWarnsOnlyForActiveOutOfTermCoursesInThisSchedule() {
        val late = CourseEntity(
            id = 1, name = "晚周课程", teacher = null, location = null, weekday = 1,
            periods = listOf(1), weeks = listOf(18), weekParity = WeekParity.ALL, note = null, scheduleId = 1
        )
        val courses = listOf(
            late,
            late.copy(id = 2, weeks = listOf(16)),
            late.copy(id = 3, weekParity = WeekParity.ODD),
            late.copy(id = 4, scheduleId = 2),
            late.copy(id = 5, weeks = listOf(2, 17))
        )

        assertEquals(listOf(1L, 5L), coursesOutsideShortenedTerm(courses, 1, 20, 16).map(CourseEntity::id))
        assertTrue(coursesOutsideShortenedTerm(courses, 1, 16, 20).isEmpty())
        assertTrue(coursesOutsideShortenedTerm(courses, 1, 20, 20).isEmpty())
        assertEquals(listOf(18), late.weeks)
    }

    @Test
    fun datesBeforeActualOpeningDayDoNotBecomeWeekOne() {
        assertNull(scheduleWeekForDateOrNull(config, LocalDate.of(2026, 9, 1)))
        assertEquals(1, scheduleWeekForDateOrNull(config, LocalDate.of(2026, 9, 2)))
    }

    @Test
    fun databaseTermStateDistinguishesOutsideTermFromWeekOne() {
        assertEquals(ScheduleTermState.UPCOMING, derivedScheduleTermState(config, LocalDate.of(2026, 9, 1)))
        assertEquals(ScheduleTermState.ACTIVE, derivedScheduleTermState(config, LocalDate.of(2026, 9, 2)))
        assertEquals(ScheduleTermState.ENDED, derivedScheduleTermState(config, LocalDate.of(2026, 9, 14)))
        assertEquals(
            ScheduleTermState.UPCOMING,
            config.withDerivedScheduleTermState(LocalDate.of(2026, 9, 1)).termState
        )
        // currentWeek remains the manual fallback; termState is the authoritative marker.
        assertEquals(1, config.withDerivedScheduleTermState(LocalDate.of(2026, 9, 1)).currentWeek)
    }

    @Test
    fun finalTeachingWeekEndsOnItsSunday() {
        assertEquals(LocalDate.of(2026, 9, 13), scheduleTermEndDate(config))
        assertEquals(2, scheduleWeekForDateOrNull(config, LocalDate.of(2026, 9, 13)))
        assertNull(scheduleWeekForDateOrNull(config, LocalDate.of(2026, 9, 14)))
        assertFalse(isAfterScheduleTerm(config, LocalDate.of(2026, 9, 13)))
        assertTrue(isAfterScheduleTerm(config, LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun automaticModeKeepsManualFallbackBeforeTheTerm() {
        val storedWeek = resolveScheduleCurrentWeek(
            baseConfig = config,
            totalWeeks = 2,
            manualCurrentWeek = 2,
            termStartDate = "2026-09-02",
            autoCurrentWeek = true,
            today = LocalDate.of(2026, 9, 1)
        )
        assertEquals(2, storedWeek)
        assertEquals("暂未开学", scheduleTermStatusLabel(config, LocalDate.of(2026, 9, 1)))
        assertEquals("学期已结束", scheduleTermStatusLabel(config, LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun automaticModePromptsForMissingStartDateWithoutGuessingTheWeek() {
        val newSchedule = defaultConfig().copy(autoCurrentWeek = true)
        val today = LocalDate.of(2026, 9, 25)

        assertEquals(1, effectiveCurrentWeek(newSchedule, today))
        assertEquals("待设置开学日期", scheduleTermStatusLabel(newSchedule, today))
        assertEquals("待设置开学日期", scheduleTermStatusDescription(newSchedule, today))
    }
}
