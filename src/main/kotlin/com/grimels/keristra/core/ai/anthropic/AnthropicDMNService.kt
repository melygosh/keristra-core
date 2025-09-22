package com.grimels.keristra.core.ai.anthropic

import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage

interface AnthropicDMNService {

    @SystemMessage(
        """
You are DMN 1.3 decision table XML generator. Return only and only DMN XML code for given rules without any other text. 
RETURN NOTHING EXCEPT XML CODE IT'S VERY IMPORTANT!
Here are some rules you need to follow in <text> tags:
Do not use <= or >= or > or < in <expression> tag use &lt;= or &gt;= or &gt; or &lt; instead.
If range is between 2 values specified then use (value1..value2) or [value1..value2] for strict range, ensure structure is valid.
If only 1 number specified then use &gt; or &lt; for greater or less than.
If entry is empty then leave <text></text> empty instead of <text>-</text>.
<example> 
    <?xml version=\"1.0\" encoding=\"UTF-8\"?>
    <definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"definitions\" name=\"definitions\" namespace=\"http://camunda.org/schema/1.0/dmn\">
      <decision id=\"decision\" name=\"Decision Table Configuration\">
        <decisionTable id=\"decisionTable\" hitPolicy=\"RULE ORDER\">
          <input id=\"input-1\" label=\"<variableNameFromObjectSchema>\">
            <inputExpression id=\"input-1-exp\" typeRef=\"string\" expressionLanguage=\"feel\"> <text> <variableNameFromSchema> </text> </inputExpression>
          </input>
          <output id=\"output-1\" label=\"<variableNameFromRulesAfterThenStatement>\" name=\"<variableNameFromRulesAfterThenStatement>\" typeRef=\"string\" />
          <rule id=\"rule-1\">
            <inputEntry id=\"rule-1-input-1\">
              <text></text>
            </inputEntry>
            <outputEntry id=\"rule-1-output-1\">
              <text></text>
            </outputEntry>
          </rule>
        </decisionTable>
      </decision>
    </definitions>
</example>
    """
    )
    fun generateDMNTable(@UserMessage request: String): String

    @SystemMessage(
        """
You are generator of JSON array containing an objects that fulfills the structure from <objectSchema> and the values from <rules>.
So that the generated objects can be used to test the decision table that was created based on the same <rules> and <objectSchema> in the format below:
[
  {
    "name": "<testCaseName>",
    "description": "<testCaseDescription>",
    "expected": [
      { ... } // object that matches <rules> expected output (as key-value pairs)
      // if hit policy is FIRST or UNIQUE then only one object is expected
      // if hit policy is RULE ORDER or COLLECT then multiple objects can be expected
    ],
    "object": { ... } // object that matches the <objectSchema> and the values from <rules>
  }
  ...
]
Return only and only JSON code for given rules by the schema you receive with no other text. 
RETURN NOTHING EXCEPT JSON CODE IT'S VERY IMPORTANT!
    """
    )
    fun generateEntities(@UserMessage request: String): String
}
