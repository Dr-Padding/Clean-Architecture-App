package com.example.cleanarchitecturetest.data.profile

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalProfileItemsParserTest {

    @Test
    fun parse_skipsEmptySections_andReadsItems() {
        val json = """
            [[{"title":"A","web_title":"WA","link":"https://a"}],
             [],
             [{"title":"B","web_title":"WB","link":"https://b"}]]
        """.trimIndent()
        val el = JsonParser.parseString(json)
        val sections = AdditionalProfileItemsParser.parse(el)!!
        assertEquals(3, sections.size)
        assertEquals(1, sections[0].size)
        assertEquals("A", sections[0][0].title)
        assertTrue(sections[1].isEmpty())
        assertEquals("B", sections[2][0].title)
        assertTrue(AdditionalProfileItemsParser.hasDisplayableItems(sections))
    }

    @Test
    fun elementFromProfileJson_prefersNamedKeys() {
        val profile = JsonParser.parseString(
            """{"drv_additional_profile_items":[[{"title":"D","link":"https://d"}]],"driver_additional_items":[]}"""
        ).asJsonObject
        val el = AdditionalProfileItemsParser.elementFromProfileJson(profile, isDriver = true)
        assertNotNull(el)
        val sections = AdditionalProfileItemsParser.parse(el!!)!!
        assertEquals(1, sections[0].size)
        assertEquals("D", sections[0][0].title)
    }

    @Test
    fun hasDisplayableItems_falseWhenAllSectionsEmpty() {
        val json = "[[],[]]"
        val sections = AdditionalProfileItemsParser.parse(JsonParser.parseString(json))!!
        assertFalse(AdditionalProfileItemsParser.hasDisplayableItems(sections))
    }
}
