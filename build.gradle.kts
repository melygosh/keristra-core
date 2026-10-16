import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
}

group = "com.grimels"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation("org.camunda.bpm:camunda-engine:7.23.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j
    implementation("dev.langchain4j:langchain4j:1.5.0")
    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-anthropic
    implementation("dev.langchain4j:langchain4j-anthropic:1.5.0")
    // OpenAI-compatible endpoints: Ollama, LM Studio, llama.cpp server
    implementation("dev.langchain4j:langchain4j-open-ai:1.5.0")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.5.0-beta11")
    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-voyage-ai
//    implementation("dev.langchain4j:langchain4j-voyage-ai:1.5.0-beta11")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
