package com.grimels.keristra.core

import org.camunda.bpm.dmn.engine.DmnEngine
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KeristraConfig {

    @Bean
    fun createDmnEngine(): DmnEngine {
        // create default DMN engine configuration
        val configuration = DmnEngineConfiguration
            .createDefaultDmnEngineConfiguration()

        // build a new DMN engine
        val dmnEngine = configuration.buildEngine()
        return dmnEngine
    }

}