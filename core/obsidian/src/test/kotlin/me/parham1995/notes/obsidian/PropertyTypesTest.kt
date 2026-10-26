package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PropertyTypesTest {
    @Test
    fun `each property's type is read`() {
        val text = """{"types": {"roles": "multitext", "rating": "number", "odd": {"x": 1}}}"""
        assertThat(PropertyTypes.parse(text)).containsExactly("roles", "multitext", "rating", "number")
    }

    @Test
    fun `a file with no types is none`() {
        assertThat(PropertyTypes.parse("{}")).isEmpty()
        assertThat(PropertyTypes.parse("[]")).isEmpty()
    }
}
