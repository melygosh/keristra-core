# Automated Generation and Validation of Decision Model and Notation Tables Using Large Language Models

## Abstract

This chapter presents a system for automated generation and validation of Decision Model and Notation (DMN) decision tables from natural language business rule specifications. The proposed approach leverages large language models (LLMs) integrated within a Retrieval-Augmented Generation (RAG) pipeline to transform unstructured textual rule descriptions into formally executable DMN XML artefacts compliant with the Camunda DMN 1.3 engine. A self-validating iterative generation loop is introduced, wherein the LLM independently produces both the decision table and its corresponding test suite from the same rule specification. The generated artefact is subsequently evaluated against the synthesised test cases using a deterministic DMN execution engine, and the generation process is repeated until correctness criteria are satisfied. The system is implemented as a service-oriented architecture built on the Spring Boot framework with Kotlin, employing the LangChain4J orchestration framework for LLM interaction and Voyage AI embedding models for semantic retrieval.

## 1. Introduction

Decision Model and Notation (DMN) is an industry standard maintained by the Object Management Group (OMG) for modelling and executing business decisions in a platform-independent manner. DMN decision tables provide a tabular representation of conditional business logic, enabling both technical and non-technical stakeholders to reason about decision behaviour. However, the manual authoring of DMN artefacts from prose-form business rules remains labour-intensive, error-prone, and requires specialised knowledge of the DMN specification, FEEL (Friendly Enough Expression Language), and the constraints of the target execution engine.

Recent advances in large language models have demonstrated their capacity to generate structured artefacts from natural language descriptions across diverse domains, including code synthesis, database query generation, and configuration file authoring. This work investigates the feasibility of employing LLMs as automated translators of natural language business rules into standards-compliant DMN decision tables, with emphasis on generation correctness, structural validity, and semantic fidelity to the source specification.

The principal contributions of this work are as follows:

1. A dual-invocation architecture in which an LLM generates both the decision table artefact and an independent test suite from the same natural language input, enabling automated self-validation without human-authored ground truth.
2. An iterative generation-validation loop that autonomously retries the generation process upon validation failure, achieving high success rates across diverse rule complexities.
3. A schema-grounded prompting strategy that constrains the LLM output space by providing domain data schemas, thereby ensuring that generated FEEL expressions reference valid variable paths.
4. A Retrieval-Augmented Generation (RAG) pipeline employing Voyage AI embeddings for semantic similarity retrieval, enabling few-shot in-context learning from a curated corpus of exemplar DMN transformations.

## 2. Related Work

### 2.1 Decision Model and Notation

DMN, standardised by the OMG (OMG, 2019), provides a formal notation for decision modelling. Decision tables, the most widely adopted DMN construct, specify input conditions and corresponding output values in a tabular form. The Camunda DMN engine (Camunda, 2024) implements a subset of the DMN specification with support for multiple hit policies (UNIQUE, FIRST, RULE ORDER, COLLECT), FEEL expression evaluation, and integration with business process management workflows.

### 2.2 Code Generation Using Large Language Models

Large language models such as GPT-4 (OpenAI, 2023) and Claude (Anthropic, 2024) have demonstrated strong performance in code generation tasks, as evaluated on benchmarks including HumanEval and MBPP. The application of LLMs to domain-specific structured artefact generation — including SQL, YAML configurations, and XML schemas — has been explored in recent literature, though their application to DMN generation remains largely unexamined.

### 2.3 Retrieval-Augmented Generation

Retrieval-Augmented Generation (RAG), introduced by Lewis et al. (2020), augments LLM prompts with relevant context retrieved from an external knowledge base. By grounding the generation process in domain-specific exemplars, RAG mitigates hallucination and improves output fidelity. The present work employs RAG to provide the LLM with curated DMN generation examples, reducing reliance on the model's parametric knowledge of the DMN specification.

## 3. System Architecture

The proposed system is realised as a service-oriented application comprising four principal subsystems: (i) the AI orchestration layer, (ii) the DMN generation and validation pipeline, (iii) the decision table execution engine, and (iv) the domain context management layer. The architectural overview is presented below.

### 3.1 Technology Stack

The system is implemented in Kotlin targeting Java 21 on the JVM platform, utilising Spring Boot 3.3.0 as the application framework. AI integration is mediated through the LangChain4J 1.5.0 orchestration framework, which provides declarative AI service definitions, content retrieval pipelines, and model abstraction. The Camunda DMN Engine 7.23.0 serves as the deterministic execution runtime for parsing and evaluating generated DMN artefacts.

### 3.2 AI Orchestration Layer

The AI orchestration layer is responsible for interfacing with the LLM and managing the RAG pipeline. Two distinct AI-powered operations are exposed through a declarative service interface:

**DMN Table Generation.** Given a natural language rule specification and a domain data schema, the LLM produces a complete Camunda-compatible DMN 1.3 XML document. The system prompt constrains the model to emit only valid XML, specifies FEEL expression encoding conventions (e.g., the use of XML entity references for comparison operators), and provides a structural template that illustrates the expected DMN element hierarchy.

**Test Case Synthesis.** Given the same rule specification and schema, the LLM generates a JSON array of test case objects. Each test case comprises: (a) an input object conforming to the domain schema, instantiated with values that exercise a specific decision path, and (b) an expected output object reflecting the anticipated decision table evaluation result. The test case generator accounts for the semantics of DMN hit policies — producing a single expected output for FIRST and UNIQUE policies, and multiple expected outputs for RULE ORDER and COLLECT policies.

Both operations are implemented using the LangChain4J AI Services abstraction, which dynamically generates proxy implementations from annotated interface definitions. System-level instructions are specified declaratively via annotations and are injected into the LLM context alongside any RAG-retrieved content prior to each invocation.

### 3.3 Retrieval-Augmented Generation Pipeline

The RAG pipeline enriches LLM prompts with domain-relevant exemplars retrieved from a vector store. The pipeline comprises the following components:

**Embedding Model.** Voyage AI (Voyage-3) is employed to compute dense vector representations of both the query text and the stored document segments. The Voyage AI model provides high-quality semantic embeddings optimised for retrieval tasks, enabling accurate similarity matching between user-provided rule specifications and curated DMN generation examples.

**Vector Store.** An in-memory embedding store maintains the indexed document segments. At application initialisation, a corpus of reference documents — including annotated DMN generation examples, the DMN specification schema (XSD), and structured training data — is ingested, segmented using recursive text splitting with configurable segment sizes and overlap, and embedded into the vector store.

**Content Retriever.** An embedding-based content retriever is configured to return the top-k (k=6) most semantically similar segments exceeding a minimum cosine similarity threshold of 0.8. Retrieved segments are automatically prepended to the user message by the LangChain4J AI Services framework, providing in-context exemplars without explicit prompt engineering for each invocation.

### 3.4 Schema-Grounded Prompting

A critical challenge in DMN generation from natural language is the mapping of informal variable references in prose-form rules to formally qualified variable paths in the generated FEEL expressions. For instance, a rule mentioning "credit score" must be translated to the path `application.creditScore` as defined in the domain schema.

To address this, the system employs a schema-grounded prompting strategy. Each generation request includes the full domain data schema (in JSON Schema or XML format) as part of the prompt context. The LLM is explicitly instructed to resolve variable references against the provided schema, ensuring that generated FEEL expressions contain valid, navigable property paths. When a variable is not directly present in the schema, the model is instructed to derive the value from available schema properties using domain knowledge.

The schema resolution mechanism maps domain context identifiers (e.g., "mortgage", "education") to their corresponding schema definitions stored in the application's resource directory. This enables the system to operate across multiple business domains without modification to the generation pipeline.

### 3.5 Iterative Generation-Validation Loop

The core contribution of this work is the iterative generation-validation loop, which autonomously ensures the correctness of generated DMN artefacts. The process proceeds as follows:

1. **Test Case Synthesis.** The LLM generates a set of test cases from the provided rules and schema.
2. **DMN Generation.** The LLM generates a DMN XML document from the same rules and schema.
3. **Response Normalisation.** The raw LLM output undergoes post-processing to remove formatting artefacts (code fences, whitespace characters) and extract the XML content.
4. **DMN Parsing.** The normalised XML is parsed using the Camunda DMN model API. If parsing fails (due to malformed XML or missing required elements), the attempt is recorded as invalid.
5. **Test Case Execution.** Each synthesised test case is instantiated as a Camunda variable map, and the parsed decision table is evaluated against it using the Camunda DMN engine.
6. **Result Comparison.** For each test case, the actual evaluation output is compared against the expected output using type-flexible equality semantics. The comparison accounts for type coercion between JSON numeric representations (IEEE 754 double-precision) and the Camunda engine's native type system (integer, long, float, double, boolean).
7. **Iteration Control.** If all test cases pass, the generated DMN artefact is returned along with the number of generation attempts required. If any test case fails, the entire process (including test case regeneration) is repeated, up to a configurable maximum of N iterations (default: 10). If the maximum is exceeded, the system reports a generation failure.

This approach eliminates the requirement for human-authored test suites, as the LLM serves as both the generator and the oracle. The independent generation of artefact and test suite from the same specification provides a form of N-version verification, where disagreement between the two outputs indicates a potential error in either the DMN table or the test cases.

### 3.6 Decision Table Evaluation Engine

The decision table evaluation subsystem provides a deterministic execution environment for generated DMN artefacts. Given a DMN XML document and a collection of domain data objects, the subsystem:

1. Parses the XML into a Camunda `DmnModelInstance`.
2. Extracts the decision element with the canonical identifier "decision".
3. Converts each domain object to a flat or nested `Map<String, Any?>` representation using Kotlin reflection, recursively traversing data class properties to produce a structure navigable by FEEL expressions.
4. Instantiates a Camunda `VariableMap` from the converted object.
5. Evaluates the decision table against the variable map, producing a `DmnDecisionTableResult` containing the matched rule outputs.

This subsystem supports evaluation against multiple domain contexts, each backed by a JSON-based repository that loads test data from the application's resource directory.

### 3.7 REST API

The system exposes three principal HTTP endpoints for external integration:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/anthropic-dmn/generate` | POST | Accepts natural language rules and a domain context identifier; returns a validated DMN XML artefact and the number of generation attempts |
| `/api/decision-tables/test` | POST | Accepts a DMN XML document and a domain context; evaluates the decision table against all data records in the specified domain repository |
| `/api/decision-tables/validate` | POST | Accepts a DMN XML document, rules, and context; generates test cases and validates the decision table against them |

## 4. Domain Modelling

The system supports multiple business domains, each defined by a data schema, a set of domain entity classes, and a JSON-backed data repository. The domain model architecture follows a consistent pattern: a Kotlin data class hierarchy defines the domain entities, a JSON Schema or XML schema document specifies the structural contract, and a repository class loads instance data from JSON files at application startup.

### 4.1 Mortgage Application Domain

The primary evaluation domain models mortgage loan applications. The domain schema encompasses:

- **Application metadata**: credit score, loan horizon, income stability classification, interest rate sensitivity, fixed-rate preference indicator.
- **Financial parameters**: loan amount, collateral valuation, debt-to-income ratio (DTI), loan-to-value ratio (LTV), income, housing expenses, outstanding debts.
- **Derived classifications**: mortgage product assignment (e.g., Fixed-Rate 30-Year, Adjustable-Rate 7/1, Manual Review).

### 4.2 Additional Domains

The system's generality is demonstrated through additional domain contexts, including university admissions (with subject-specific entrance examination scores), vehicle asset products (with hierarchical asset segment classifications), body mass index calculations, and hotel room categorisation. Each domain follows the same schema-driven generation pattern, validating the approach's domain independence.

## 5. Experimental Evaluation

### 5.1 Experimental Setup

The system was evaluated across multiple business rule complexities and domain contexts. Experiments were conducted using Anthropic's Claude language model with the following configuration parameters: maximum output token length of 8,192, system message caching enabled, and temperature at the model default. The iterative validation loop was configured with a maximum of 10 generation attempts per request.

### 5.2 Vehicle Asset Product Experiment

A comprehensive experiment was conducted using a RULE ORDER hit policy decision table with 9 business rules governing vehicle asset product assignments across multiple vehicle segment categories (electric, commercial, hybrid). The experiment comprised 63 independent generation-validation-evaluation trials.

Each trial involved:
1. Submission of the full rule set to the generation endpoint.
2. Iterative DMN generation and validation (up to 10 attempts).
3. Evaluation of the generated DMN against the complete domain dataset.
4. Programmatic verification of all rule outcomes against expected results.

The verification procedure assessed correctness across all rule categories: universal registration fees, conditional insurance assignments, segment-specific service and maintenance provisions with subsidy-dependent default amounts, and establishment fees.

### 5.3 Mortgage Product Assignment Experiment

A second experiment evaluated the system's capacity to generate FIRST hit policy decision tables for mortgage product assignment, incorporating:
- Numeric range conditions (credit scores, DTI ratios, LTV ratios).
- Boolean preference indicators.
- Enumerated stability classifications.
- Boundary conditions and null-value handling.

Test cases were designed to exercise priority ordering of rules (e.g., ensuring that fixed-preference rules take precedence over ARM qualification rules) and to validate correct handling of boundary values at rule thresholds.

## 6. Discussion

### 6.1 Dual-Generation Self-Validation

The independent generation of both the DMN artefact and its test suite from the same specification constitutes a form of diversified automatic validation. Since both outputs are produced by the same LLM but through distinct prompting strategies (one targeting XML generation, the other targeting JSON test case synthesis), systematic errors that affect both outputs identically are unlikely. Discrepancies between the generated DMN behaviour and the synthesised test expectations serve as reliable indicators of generation errors.

However, this approach carries an inherent limitation: if the LLM consistently misinterprets a rule in the same manner across both generation modes, the error may go undetected. This risk is mitigated by the stochastic nature of LLM generation — regeneration attempts are not deterministic replicas, and the test cases are independently regenerated on each iteration, providing varied perspectives on the rule specification.

### 6.2 Schema Grounding and Variable Resolution

The provision of domain schemas as prompt context proves essential for correct FEEL expression generation. Without schema grounding, the LLM must infer variable paths from the rule text alone, frequently producing syntactically valid but semantically incorrect property references. Schema grounding transforms the generation task from open-ended code synthesis to constrained mapping between natural language terms and formally defined schema elements.

### 6.3 Type-Flexible Validation

The impedance mismatch between JSON's numeric representation (where all numbers are represented as IEEE 754 double-precision floating point) and the Camunda DMN engine's native type system necessitates a type-flexible comparison strategy. The implemented approach performs bidirectional type coercion during result comparison, successively attempting double, float, integer, long, and boolean interpretations. While pragmatic, this approach may mask genuine type errors in edge cases and represents an area for future refinement.

### 6.4 Hit Policy Complexity

The experiments reveal that RULE ORDER hit policy tables, which may produce multiple output rows per input, present greater generation challenges than FIRST or UNIQUE policies. The LLM must correctly reason about rule ordering, ensure that universal rules (applicable to all inputs) do not inadvertently shadow more specific rules, and produce test cases with the correct number of expected output entries. The iterative validation loop compensates for this increased difficulty by allowing multiple generation attempts.

## 7. Conclusions and Future Work

This work demonstrates the feasibility of using large language models to automate the generation of DMN decision tables from natural language business rule specifications. The iterative self-validating generation loop, combined with schema-grounded prompting and RAG-based in-context learning, produces standards-compliant DMN artefacts that correctly implement the specified business logic across diverse domains and hit policy types.

Future work will address the following directions:

1. **Feedback-informed regeneration.** Incorporating validation error messages from failed attempts into subsequent generation prompts, enabling the LLM to learn from its prior mistakes within a single generation session.
2. **Human-in-the-loop refinement.** Extending the system with interactive capabilities for domain experts to review, annotate, and refine generated DMN artefacts before deployment.
3. **Extended DMN coverage.** Supporting additional DMN constructs beyond decision tables, including decision requirement diagrams and business knowledge models.
4. **Quantitative benchmarking.** Establishing standardised benchmarks for DMN generation quality, including metrics for structural validity, semantic correctness, rule coverage, and generation efficiency.
5. **Production-grade RAG deployment.** Migrating the vector store from an in-memory implementation to a persistent, scalable solution suitable for enterprise deployment.

## References

- Anthropic. (2024). Claude: A family of AI assistants. Technical Report.
- Camunda. (2024). Camunda DMN Engine Documentation. https://docs.camunda.org/manual/latest/reference/dmn/
- Lewis, P., Perez, E., Piktus, A., et al. (2020). Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks. *Advances in Neural Information Processing Systems*, 33, 9459–9474.
- Object Management Group. (2019). Decision Model and Notation (DMN), Version 1.3. OMG Document Number: formal/2019-12-01.
- OpenAI. (2023). GPT-4 Technical Report. arXiv:2303.08774.
