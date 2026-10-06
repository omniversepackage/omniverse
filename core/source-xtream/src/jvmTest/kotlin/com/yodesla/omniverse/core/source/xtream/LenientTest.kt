package com.yodesla.omniverse.core.source.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LenientTest {

    private fun obj(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    @Test
    fun strRules() {
        val o = obj("""{"a":"plain","b":"  padded  ","c":"","d":"null","e":null,"f":42,"g":4.5,"h":true}""")
        assertEquals("plain", o.str("a"))
        assertEquals("padded", o.str("b"))
        assertNull(o.str("c"))
        assertNull(o.str("d"))
        assertNull(o.str("e"))
        assertEquals("42", o.str("f"))
        assertEquals("4.5", o.str("g"))
        assertEquals("true", o.str("h"))
        assertNull(o.str("missing"))
    }

    @Test
    fun intRules() {
        val o = obj("""{"a":7,"b":"7","c":"7.5","d":"junk","e":null,"f":2.0}""")
        assertEquals(7, o.int("a"))
        assertEquals(7, o.int("b"))
        assertNull(o.int("c"))
        assertNull(o.int("d"))
        assertNull(o.int("e"))
        assertEquals(2, o.int("f"))
        assertNull(o.int("missing"))
    }

    @Test
    fun longFloatRules() {
        val o = obj("""{"a":1234567890123,"b":"1234567890123","c":"7.5","d":7.5,"e":"junk","f":3}""")
        assertEquals(1234567890123L, o.long("a"))
        assertEquals(1234567890123L, o.long("b"))
        assertNull(o.long("c"))
        assertEquals(7.5f, o.float("c"))
        assertEquals(7.5f, o.float("d"))
        assertNull(o.float("e"))
        assertEquals(3f, o.float("f"))
        assertNull(o.float("missing"))
    }

    @Test
    fun boolRules() {
        val o = obj("""{"a":true,"b":false,"c":1,"d":0,"e":"1","f":"0","g":"true","h":"false","i":"junk","j":2}""")
        assertEquals(true, o.bool("a"))
        assertEquals(false, o.bool("b"))
        assertEquals(true, o.bool("c"))
        assertEquals(false, o.bool("d"))
        assertEquals(true, o.bool("e"))
        assertEquals(false, o.bool("f"))
        assertEquals(true, o.bool("g"))
        assertEquals(false, o.bool("h"))
        assertNull(o.bool("i"))
        assertNull(o.bool("j"))
        assertNull(o.bool("missing"))
    }

    @Test
    fun strListRules() {
        val o = obj("""{"a":["x","y"],"b":["1",2],"c":"solo","d":5,"e":[],"f":[1,null,"z"],"g":"null"}""")
        assertEquals(listOf("x", "y"), o.strList("a"))
        assertEquals(listOf("1", "2"), o.strList("b"))
        assertEquals(listOf("solo"), o.strList("c"))
        assertEquals(listOf("5"), o.strList("d"))
        assertEquals(emptyList<String>(), o.strList("e"))
        assertEquals(listOf("1", "z"), o.strList("f"))
        assertEquals(emptyList<String>(), o.strList("g"))
        assertEquals(emptyList<String>(), o.strList("missing"))
    }

    @Test
    fun objAndArrRules() {
        val o = obj("""{"a":{"x":1},"b":[1,2],"c":"s","d":3}""")
        assertEquals("1", o.obj("a")?.str("x"))
        assertNull(o.obj("c"))
        assertEquals(2, o.arr("b")?.size)
        assertNull(o.arr("d"))
        assertNull(o.obj("missing"))
        assertNull(o.arr("missing"))
    }

    @Test
    fun htmlEntities() {
        assertEquals("a & b < c > d", decodeHtmlEntities("a &amp; b &lt; c &gt; d"))
        assertEquals("say \"hi\" 'yo'", decodeHtmlEntities("say &quot;hi&quot; &#39;yo&#39;"))
        assertEquals("'postrophe", decodeHtmlEntities("&apos;postrophe"))
        assertEquals("H A A", decodeHtmlEntities("&#72; &#x41; &#65;"))
        assertEquals("&unknown;", decodeHtmlEntities("&unknown;"))
        assertEquals("no entities", decodeHtmlEntities("no entities"))
    }

    @Test
    fun lenientBase64DecodesStandardAndUrlSafe() {
        assertEquals("Hello", lenientBase64("SGVsbG8="))
        assertEquals("Hello", lenientBase64("SGVsbG8"))
        assertEquals("Show 692", lenientBase64("U2hvdyA2OTI="))
        assertEquals(lenientBase64("////")!!, lenientBase64("____")!!)
        assertEquals("", lenientBase64(""))
        assertNull(lenientBase64(null))
    }

    @Test
    fun lenientBase64InvalidUtf8UsesReplacement() {
        assertEquals("\uFFFD", lenientBase64("vw=="))
        assertEquals("\uFFFD\uFFFD\uFFFD", lenientBase64("////"))
    }

    @Test
    fun lenientBase64NonBase64ReturnsInputUnchanged() {
        assertEquals("hello!", lenientBase64("hello!"))
        assertEquals("abcde", lenientBase64("abcde"))
        assertEquals("has space & junk", lenientBase64("has space & junk"))
    }
}
