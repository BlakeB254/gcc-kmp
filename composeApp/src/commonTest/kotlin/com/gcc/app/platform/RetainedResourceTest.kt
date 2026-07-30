package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RetainedResourceTest {
    @Test
    fun returnsNullWhenAcquireReturnsNull() {
        var released = 0
        val result = useRetainedResourceOrNull<String, String>(
            acquire = { null },
            release = { released++ },
            block = { "unused" },
        )

        assertNull(result)
        assertEquals(0, released)
    }

    @Test
    fun releasesResourceAfterSuccessfulUse() {
        val events = mutableListOf<String>()

        val result = useRetainedResourceOrNull(
            acquire = {
                events += "acquire"
                "frame"
            },
            release = { resource -> events += "release:$resource" },
            block = { resource ->
                events += "use:$resource"
                "jpeg"
            },
        )

        assertEquals("jpeg", result)
        assertEquals(listOf("acquire", "use:frame", "release:frame"), events)
    }

    @Test
    fun releasesResourceWhenBlockThrows() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            useRetainedResourceOrNull(
                acquire = {
                    events += "acquire"
                    "frame"
                },
                release = { resource -> events += "release:$resource" },
                block = { resource ->
                    events += "use:$resource"
                    error("boom")
                },
            )
        }

        assertEquals(listOf("acquire", "use:frame", "release:frame"), events)
    }
}
