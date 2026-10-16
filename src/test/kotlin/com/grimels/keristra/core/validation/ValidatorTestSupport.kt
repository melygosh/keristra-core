package com.grimels.keristra.core.validation

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.camunda.bpm.dmn.engine.DmnEngine
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration

internal val testEngine: DmnEngine by lazy {
    DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine()
}

/** Parses test cases the same way production does (Gson: every JSON number becomes a Double). */
internal fun cases(json: String): List<Map<String, Any?>> =
    Gson().fromJson(json, object : TypeToken<List<Map<String, Any?>>>() {}.type)

internal data class In(val expression: String, val typeRef: String)
internal data class Out(val name: String, val typeRef: String = "string")

/** Builds a single-decision DMN in the shape the generator produces (decision id "decision"). */
internal fun dmn(hitPolicy: String, inputs: List<In>, output: Out, rules: List<Pair<List<String>, String>>): String {
    val inputXml = inputs.mapIndexed { i, input ->
        """<input id="input-$i" label="in$i">
             <inputExpression id="input-$i-exp" typeRef="${input.typeRef}"><text>${input.expression}</text></inputExpression>
           </input>"""
    }.joinToString("\n")
    val ruleXml = rules.mapIndexed { r, (entries, outputEntry) ->
        val entryXml = entries.mapIndexed { i, e -> """<inputEntry id="r$r-i$i"><text>$e</text></inputEntry>""" }.joinToString("")
        """<rule id="rule-$r">$entryXml<outputEntry id="r$r-o"><text>$outputEntry</text></outputEntry></rule>"""
    }.joinToString("\n")
    return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="definitions" namespace="http://camunda.org/schema/1.0/dmn">
  <decision id="decision" name="Test decision">
    <decisionTable id="decisionTable" hitPolicy="$hitPolicy">
      $inputXml
      <output id="output-1" label="${output.name}" name="${output.name}" typeRef="${output.typeRef}" />
      $ruleXml
    </decisionTable>
  </decision>
</definitions>"""
}

/** Mortgage-like FIRST table WITHOUT a default rule: the exact shape behind finding F1. */
internal val firstNoDefault = dmn(
    "FIRST",
    listOf(In("application.fixedPreference", "boolean"), In("application.creditScore", "double")),
    Out("product"),
    listOf(
        listOf("true", "") to "\"Fixed30\"",
        listOf("", "&gt;= 700") to "\"ARM7/1\"",
    ),
)

internal fun app(fixedPreference: Boolean, creditScore: Int) =
    """{"application": {"fixedPreference": $fixedPreference, "creditScore": $creditScore}}"""
