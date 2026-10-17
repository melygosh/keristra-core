package com.grimels.keristra.core.experiment

import java.nio.file.Path
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * `--spring.profiles.active=experiment --keristra.experiment.config=experiments/<name>.json`: runs one experiment
 * without the web server, writes `<output-dir>/<runId>.jsonl` and exits (0 on success, 1 on failure).
 */
@Component
@Profile("experiment")
class ExperimentCommand(
    private val runner: ExperimentRunner,
    private val context: ConfigurableApplicationContext,
    @Value("\${keristra.experiment.config}") private val configFile: String,
    @Value("\${keristra.experiment.output-dir:runs}") private val outputDir: String,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(ExperimentCommand::class.java)

    override fun run(args: ApplicationArguments) {
        val exitCode = try {
            val log = runner.run(RunConfig.read(Path.of(configFile)), Path.of(outputDir))
            logger.info("Experiment finished: $log")
            0
        } catch (e: Exception) {
            logger.error("Experiment failed: ${e.message}", e)
            1
        }
        exitProcess(SpringApplication.exit(context, { exitCode }))
    }
}
