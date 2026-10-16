package com.grimels.keristra.core.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** Independence of the two generation tracks (REVISION_PLAN 4.2) and of the verifier (F4). */
class ArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.grimels.keristra.core")

    @Test
    fun `the DMN track does not see the test track`() {
        noClasses().that().resideInAPackage("..generation.dmn..")
            .should().dependOnClassesThat().resideInAPackage("..generation.tests..")
            .check(classes)
    }

    @Test
    fun `the test track does not see the DMN track`() {
        noClasses().that().resideInAPackage("..generation.tests..")
            .should().dependOnClassesThat().resideInAPackage("..generation.dmn..")
            .check(classes)
    }

    @Test
    fun `generators do not see validation results`() {
        noClasses().that().resideInAPackage("..generation..")
            .should().dependOnClassesThat().resideInAPackage("..validation..")
            .check(classes)
    }

    @Test
    fun `the validator does not generate - no LLM or generation dependencies`() {
        noClasses().that().resideInAPackage("..validation..")
            .should().dependOnClassesThat().resideInAnyPackage("..llm..", "..generation..", "..ai..", "dev.langchain4j..")
            .check(classes)
    }
}
