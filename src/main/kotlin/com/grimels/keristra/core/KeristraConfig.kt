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

    @Bean
    fun createLogger(): org.slf4j.Logger {
        return org.slf4j.LoggerFactory.getLogger("KeristraLogger")
    }

    @Bean
    fun createGson(): com.google.gson.Gson {
        return com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .create()
    }

}