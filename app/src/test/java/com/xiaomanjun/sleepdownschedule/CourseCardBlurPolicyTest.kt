package com.xiaomanjun.sleepdownschedule

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseCardBlurPolicyTest {
    @Test
    fun liquidAndSimpleCardsKeepIndependentBlurLimits() {
        assertEquals(10f, courseCardBlurMaximum(glassEnabled = true), 0f)
        assertEquals(24f, courseCardBlurMaximum(glassEnabled = false), 0f)
    }
}
