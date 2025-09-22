package com.grimels.keristra.core.ai.anthropic

import com.grimels.keristra.core.ai.anthropic.dto.DmnGenerationRequest
import com.grimels.keristra.core.util.SchemaResolver
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/anthropic-dmn")
class AnthropicDMNController(
    private val dmnGenerator: AnthropicDMNService,
) {

    @RequestMapping(value = ["/generate"], consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.TEXT_XML_VALUE], method = [RequestMethod.POST])
    fun generateDMNTableContent(@RequestBody request: DmnGenerationRequest): String {
        if (request.rules == null || request.context == null) {
            throw IllegalArgumentException("Rules and context are required")
        }
        val chatRequest =
            """RETURN ONLY AND ONLY XML CODE IT'S VERY IMPORTANT!Generate DMN table that can be applied to following schema and rules. Make sure the table can be read horizontally.
The column headers contain the inputs and output.
Specify hitPolicy from the rules or set it as "RULE ORDER" if none is specified.
id attribute of <decision> tag has to be "decision"
If rule don't start with "create " and contains the name of the variable from object schema then replace it in <variableNameFromObjectSchema>
If <outputEntry> <text> is a string type then wrap the value in double quotes.
Use <schema> to find the correct <variable> path for <expression> from the <rules>.
<schema>
    ${SchemaResolver.getSchema(request.context)}
</schema>
<rules>
    ${request.rules}
</rules>"""
        val response: String = dmnGenerator.generateDMNTable(chatRequest)
        return response
    }



//    Replace <variable> with <variableNameFromObjectSchema> if it present in rules.
//    Specify the input variables using the object schema and rules.
    //

    // If rule is related to numeric value and range between two defined values <value1> and <value2> then use [<value1>..<value2>] as <expression>.
    //If "Else" statement is present in the rules then leave <inputEntry> <text> as empty.
    //If <outputEntry> <text> is always empty for all rows in column then remove <outputEntry> tag.
    //If between <value1> and <value2> then <expression> = <text>[<value1>..<value2>]</text>
    //If between <value1> and <value2> without <value1> then <expression> = <text>(<value1>..<value2>]</text>
    //If between <value1> and <value2> without <value2> then <expression> = <text>[<value1>..<value2>)</text>
    //If more than <value1> then <expression> = <text>&gt; <value1></text>
    //If less than <value1> then <expression> = <text>&lt; <value1></text>


    // If rules contains the name of the variable from object schema then replace it in <variableNameFromObjectSchema>.
//    If rule says "Create <variableLabel> expressions is <variableExpression> type is <variableType>" then use <input id="input-1" label="<variableLabel>"> <inputExpression id="input-1-exp" typeRef="<variableType>"><text> <variableExpression> </text></inputExpression></input> as <expression>.
    // If rule says "If <variableNameFromObjectSchema> is present" then use <variableNameFromObjectSchema> != null
    //If rule says "Create <variableLabel> expressions is <variableExpression> type is <variableType>" then use <input id="input-1" label="<variableLabel>"> <inputExpression id="input-1-exp" typeRef="<variableType>"><text> <variableExpression> </text></inputExpression></input> as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is not empty" then use count(<variableNameFromObjectSchema>) as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is empty" then use count(<variableNameFromObjectSchema>) = 0  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is more than <value1>" then use > <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is more than <value1> (without <value1>)" then use > <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is more than or equals <value1>" then use >= <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is less than <value1>" then use < <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is less than <value1> (without <value1>)" then use < <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is less than or equals <value1>" then use <= <value1>  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> is between <value1> and <value2>" then use [<value1>..<value2>]  as <expression>.
    //If rule says "If the <variableNameFromObjectSchema> contains <value>" and <variableNameFromObjectSchema> type from object schema is string then use contains(?, "<value>").
    //If rule says "If the <variableNameFromObjectSchema> contains <value>" and <variableNameFromObjectSchema> type from object schema is array then use list contains(?, "<value>").
}