package com.grimels.keristra.core.ai.anthropic

/**
 * The V0 user messages, moved verbatim out of AnthropicDMNController and DmnValidator (indentation included:
 * `trimIndent()` below depends on it). The system messages stay in [AnthropicDMNService]'s annotations.
 * `resources/prompts/{dmn,tests}/v1.txt` reproduce these pairs; PromptTemplateTest keeps them in sync.
 */
object V0Prompts {

    fun dmnUserMessage(rules: String, schema: String): String =
            """RETURN ONLY AND ONLY CAMUNDA XML CODE IT'S VERY IMPORTANT! Generate Camunda DMN table that can be applied to following schema and rules. Make sure the table can be read horizontally.
USE <schema> to find the correct <variable> path for <expression> from the <rules>. If <variable> is not present in the <schema> then try to find how to calculate it from knowledge you have.
IT IS VERY IMPORTANT THAT YOU FOLLOW THE INSTRUCTIONS BELOW TO THE LETTER!
The column headers contain the inputs and output.
Specify hitPolicy from the rules or set it as "RULE ORDER" if none is specified.
id attribute of <decision> tag has to be "decision"
If rule don't start with "create " and contains the name of the variable from object schema then replace it in <variableNameFromObjectSchema>
If <outputEntry> <text> is a string type then wrap the value in double quotes.
Use <schema> to find the correct <variable> path for <expression> from the <rules>.
<schema>
    ${schema}
</schema>
<rules>
    ${rules}
</rules>"""

    fun testsUserMessage(input: String, schema: String): String =
        """
            Generate N test cases for DMN decision table validation.
            Each test case should be a JSON object with fields matching the input schema.
            Provide as many test cases as possible, covering edge cases and typical scenarios.
            Ensure the test cases are valid according to the provided JSON schema.
            Respond only with a JSON array of test case objects, without any additional text. IT IS VERY IMPORTANT!
            <objectSchema>
                $schema
            </objectSchema>
            <rules>
                $input
            </rules>
        """.trimIndent()
}
