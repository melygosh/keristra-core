# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.grimels.keristra.core.KeristraCoreApplicationTests"

# Run the Spring Boot application
./gradlew bootRun

# Clean and rebuild
./gradlew clean build
```

## Architecture Overview

Keristra-Core is a Kotlin/Spring Boot application that uses AI (Anthropic Claude) to generate and validate DMN (Decision Model and Notation) decision tables from natural language business rules.

### Core Workflow
1. Business rules are submitted in natural language
2. Claude generates Camunda DMN XML from the rules
3. Claude generates test cases from the rules
4. The DMN table is validated against test cases
5. Valid DMN tables can evaluate against domain data contexts

### Key Packages

- `ai/anthropic/` - LangChain4J integration with Claude for DMN generation. `AnthropicDMNService` uses iterative generation with validation retry (up to 10 attempts).
- `decision/table/` - Camunda DMN engine integration. `DecisionTableEvaluationService` evaluates DMN tables against context-specific data.
- `model/` - Domain contexts (mortgage, education, user, bmi, vap, hotel). Each has a data class and a `*JsonRepo` that loads from JSON files in `resources/data-set/`.
- `validation/` - `DmnValidator` validates generated DMN against test cases with type-flexible comparison.
- `util/SchemaResolver` - Resolves JSON/XML schemas from `resources/schema/` for context definitions.

### REST API

| Endpoint | Purpose |
|----------|---------|
| `POST /api/anthropic-dmn/generate` | Generate DMN from rules using Claude |
| `POST /api/decision-tables/test` | Evaluate DMN against context data |
| `POST /api/decision-tables/validate` | Validate DMN against test cases |

### Technology Stack

- Kotlin 1.9.24, Java 21, Spring Boot 3.3.0
- Camunda DMN Engine 7.23.0
- LangChain4J 1.5.0 with Anthropic integration
- Gradle 8.7
- Voyage AI

## Domain Contexts

The primary domain is **mortgage applications** with models for loan amounts, collateral, DTI/LTV calculations. Other contexts (education, user, bmi, vap, hotel) follow the same pattern of schema + JSON data + repository.

## Configuration

API keys are configured in `application.properties` via:
- `anthropic.api.key` - Required for DMN generation
- `openai.api.key` - Legacy, unused

Data files: `resources/data-set/*.json`
Schemas: `resources/schema/*.json` and `*.xml`
