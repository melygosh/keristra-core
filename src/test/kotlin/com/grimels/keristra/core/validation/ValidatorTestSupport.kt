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

/** Two outputs, like fop-eligibility: score >= 900 -> eligible with no reason (null), >= 700 -> eligible "OK", else "SCORE". */
internal val twoOutputs = """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="definitions" namespace="http://camunda.org/schema/1.0/dmn">
  <decision id="decision" name="Two outputs">
    <decisionTable id="decisionTable" hitPolicy="FIRST">
      <input id="input-1"><inputExpression id="input-1-exp" typeRef="double"><text>application.creditScore</text></inputExpression></input>
      <output id="output-1" name="eligible" typeRef="boolean" />
      <output id="output-2" name="reason" typeRef="string" />
      <rule id="rule-1"><inputEntry id="r1-i"><text>&gt;= 900</text></inputEntry><outputEntry id="r1-o1"><text>true</text></outputEntry><outputEntry id="r1-o2"><text></text></outputEntry></rule>
      <rule id="rule-2"><inputEntry id="r2-i"><text>&gt;= 700</text></inputEntry><outputEntry id="r2-o1"><text>true</text></outputEntry><outputEntry id="r2-o2"><text>"OK"</text></outputEntry></rule>
      <rule id="rule-3"><inputEntry id="r3-i"><text>&lt; 700</text></inputEntry><outputEntry id="r3-o1"><text>false</text></outputEntry><outputEntry id="r3-o2"><text>"SCORE"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>"""
