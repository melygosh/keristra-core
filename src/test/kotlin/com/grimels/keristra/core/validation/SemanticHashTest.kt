package com.grimels.keristra.core.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SemanticHashTest {

    private val base = dmn(
        "FIRST",
        listOf(In("application.fixedPreference", "boolean"), In("application.creditScore", "double")),
        Out("product"),
        listOf(listOf("true", "") to "\"Fixed30\"", listOf("", "&gt;= 700") to "\"ARM7/1\""),
    )

    private fun hash(xml: String) = SemanticHash.of(xml)

    @Test
    fun `ids, labels and layout whitespace do not change the hash`() {
        val relabelled = base
            .replace("rule-", "r-").replace("label=\"in", "label=\"Input ").replace("name=\"Test decision\"", "name=\"Mortgage\"")
            .replace("<text>&gt;= 700</text>", "<text>  &gt;=   700 </text>")
            .replace("\n", "\n    ")

        assertThat(hash(relabelled)).isNotNull().isEqualTo(hash(base))
    }

    @Test
    fun `input column order does not change the hash`() {
        val swapped = dmn(
            "FIRST",
            listOf(In("application.creditScore", "double"), In("application.fixedPreference", "boolean")),
            Out("product"),
            listOf(listOf("", "true") to "\"Fixed30\"", listOf("&gt;= 700", "") to "\"ARM7/1\""),
        )

        assertThat(hash(swapped)).isEqualTo(hash(base))
    }

    @Test
    fun `an empty entry and a dash both mean any value`() {
        assertThat(hash(base.replace("<text></text>", "<text>-</text>"))).isEqualTo(hash(base))
    }

    @Test
    fun `a threshold change changes the hash`() {
        assertThat(hash(base.replace("&gt;= 700", "&gt;= 701"))).isNotEqualTo(hash(base))
    }

    @Test
    fun `an output value change changes the hash, including whitespace inside string literals`() {
        assertThat(hash(base.replace("\"ARM7/1\"", "\"ARM5/1\""))).isNotEqualTo(hash(base))
        assertThat(hash(base.replace("\"Fixed30\"", "\"Fixed 30\""))).isNotEqualTo(hash(base))
    }

    @Test
    fun `a hit policy change changes the hash`() {
        assertThat(hash(base.replace("hitPolicy=\"FIRST\"", "hitPolicy=\"UNIQUE\""))).isNotEqualTo(hash(base))
    }

    @Test
    fun `rule order matters under FIRST but not under UNIQUE`() {
        fun table(hitPolicy: String, rules: List<Pair<List<String>, String>>) =
            dmn(hitPolicy, listOf(In("application.creditScore", "double")), Out("product"), rules)
        val a = listOf(listOf("&gt;= 700") to "\"A\"", listOf("&lt; 700") to "\"B\"")

        assertThat(hash(table("FIRST", a.reversed()))).isNotEqualTo(hash(table("FIRST", a)))
        assertThat(hash(table("UNIQUE", a.reversed()))).isEqualTo(hash(table("UNIQUE", a)))
    }

    @Test
    fun `an unparseable table has no hash`() {
        assertThat(hash("<definitions><broken")).isNull()
    }
}
