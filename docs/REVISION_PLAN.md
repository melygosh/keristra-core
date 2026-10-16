# Keristra — revision plan for the follow-up article

Status: draft, prepared in Cowork on 2026-10-16 for execution in Claude Code.
Owner: Vladyslav Maliarenko. Line numbers refer to the working tree as of 2026-10-16 (it contains uncommitted changes on top of `5079be2`).

## 0. Why this revision exists

The follow-up article (UA version first, EN version later) makes one claim the main contribution:
**two independent generations from the same source text — DMN logic and a test suite — and their mutual verification.**
The current code cannot support that claim cleanly: the validator silently accepts tables that disagree with their oracle (F1), both tracks are hard-wired to the same model and retriever (F6), and experiment metrics can only be recovered from text logs by regex (F9).

Planned experiments the code must support:

- **E1 Fault injection.** Mutate a ground-truth DMN, measure mutant kill rate of: human tests, tests from the same model, tests from a different (local open-weight) model. No DMN generation needed.
- **E2 Correlated errors.** Generate DMN + tests with same-model vs cross-model track assignment; measure false-accept rate against human ground truth.
- **E3 Disagreement as ambiguity signal.** Ambiguous vs disambiguated rule text; strategy V3 escalates instead of regenerating the oracle.
- **E4 Regression across rule versions.** DMN generated from version N, tests from version N+1 (and reverse); measure detection of the changed input regions. First domain: ФОП single tax (versions 2024/2025/2026).

Baseline **V0 must remain reproducible** — it is the dissertation code. Do not rewrite from scratch.

## 1. Pre-flight (Vlad, before any refactoring)

1. Commit the current work-in-progress as-is (mortgage experiment files are part of the evidence).
2. `git tag v0-dissertation` on that commit.
3. `git checkout -b revision/experiment-core`.
4. **Security — do this before any push.** `application.properties` with real API keys (Anthropic, OpenAI, Voyage) is committed in `9ad9960` and `b2da9d1`. These 5 commits are not yet on `origin` (`origin/main` = `b62229f`, remote is `github.com/TorqueITS/keristra-core`). Pushing `main` as it is would publish the keys to the company org.
   - Rotate all three keys regardless.
   - Move keys to environment variables (`ANTHROPIC_API_KEY` etc.) with `${ANTHROPIC_API_KEY:}` placeholders in properties; add a git-ignored `application-local.properties`.
   - Rewrite the unpushed history to drop the secrets (e.g. `git filter-repo --replace-text`) before the first push.

## 2. Findings in the current code

| ID | Where | Problem | Impact on the article |
|---|---|---|---|
| F1 | `validation/DmnValidationResult.kt:22` | `for (item in result)` — an empty `DmnDecisionResult` skips the loop and `isValid()` returns `null` (= valid) whatever the test expected. | In the 2025-10-31 mortgage log, 789 of 3247 test executions (24.3%) returned no result while the test expected a product, and none was reported. The mutual check was half-blind. |
| F2 | same file | Returns only the first mismatch per test case and per table. | Cannot count disagreements per cycle. |
| F3 | same file, line ~27 | Mismatch message reads `object.application.id` — domain-specific, throws for any other schema. | Breaks the ФОП domain. |
| F4 | `validation/DmnValidator.kt` `generateTestCases()` | The validator itself calls the LLM to produce the oracle. | Generation of the second track lives inside the verifier — architecturally opposite to the claim of independent tracks. |
| F5 | `ai/anthropic/AnthropicDMNController.kt:50–53` | Retry loop, prompt assembly and orchestration live in the REST controller; tests are regenerated on every iteration (strategy B hard-wired); budget `< 10` tries. | Strategy A existed only by commenting code in and out; no V3. |
| F6 | `ai/anthropic/AnthropicConfig.kt:97–103` | One `AiServices` instance serves both `generateDMNTable` and `generateEntities` with the same `ChatModel` and the same `ContentRetriever`. | Impossible to put the test track on a different model. |
| F7 | `AnthropicConfig.kt:39–58` | At `5079be2` the `loadDocuments` call was removed: the in-memory embedding store is empty, so the retriever returns nothing. Embeddings are local MiniLM, not Voyage-3 (PAPER.md says Voyage-3). | The 2025-10-31 mortgage run was effectively **without RAG**. RAG must become an explicit, logged experiment factor (corpus, k, minScore). |
| F8 | `AnthropicConfig.kt:91` | Model `CLAUDE_SONNET_4_20250514`; no temperature, no seed; model id not logged. | Dissertation text says all experiments ran on "Claude 4.5 Sonnet"; the code says Sonnet 4. Every run must log the exact model id and sampling params. |
| F9 | everywhere | Metrics only in free-text logs. | Analysis = regex over 8 MB logs. Need JSONL events. |
| F10 | `build.gradle.kts:43,49` | `log4j-api`/`log4j-core` **2.6.1** — inside the Log4Shell range (CVE-2021-44228 affects 2.0-beta9 … 2.14.1). Spring Boot already uses Logback. | Remove both dependencies. |
| F11 | DI style | Nullable `@Autowired private val x: T? = null` field injection; `Logger` injected as a bean. | Move to constructor injection. |
| F12 | `DecisionTableEvaluationService`, `SchemaResolver` | `if (context == "...")` chains per domain. | Replace with a registry keyed by domain id. |
| F13 | `KeristraCoreApplicationTests.kt` | Experiments live in `contextLoads()` with large commented blocks; `validate_experiment_Mortgage` always returns `true`. | Replace with a dedicated experiment runner (section 4.6). |

## 3. Validator semantics (strict mode)

Implement **two** evaluators behind one interface:

- `LenientValidator` — byte-for-byte replica of the current `isValid()` behaviour, including F1. Needed to reproduce V0 numbers on the new harness.
- `StrictValidator` — the corrected semantics below.

Per test case the strict outcome is one of:

| Outcome | Condition |
|---|---|
| `PASS` | Result matches expected for every expected key, using type-flexible equality (keep `equalsIgnoreType`). |
| `MISMATCH` | Non-empty result, at least one key differs. Record all differing keys. |
| `EMPTY_RESULT` | Result empty, expected non-empty. **Fails** in strict mode. |
| `UNEXPECTED_RESULT` | Expected explicitly empty or null ("no rule applies"), result non-empty. |
| `MULTI_RESULT` | More than one result row under a single-hit policy (FIRST/UNIQUE/ANY) where one is expected. |
| `EVAL_ERROR` | FEEL or engine exception. |

Table-level: `PARSE_ERROR` (XML/DMN model invalid) and `SCHEMA_ERROR` (input expression references a path absent from the context schema).

The validation report contains **all** outcomes, not only the first failure.

Unit tests (required, no LLM involved): one hand-written DMN plus test cases covering every outcome above. They include the exact F1 scenario: a table without a default rule and a test expecting `Fixed30` → `EMPTY_RESULT`.

## 4. Target structure

Kotlin, Spring Boot 3.3, constructor injection, `data class` for DTOs and events. Keep the REST API working but thin. New code goes under `com.grimels.keristra.core`.

### 4.1 `llm` — provider abstraction
```kotlin
interface LlmClient {
    val modelId: String
    fun complete(request: LlmRequest): LlmResponse
}
data class LlmRequest(val system: String, val user: String, val temperature: Double?, val seed: Long?, val maxTokens: Int)
data class LlmResponse(val text: String, val modelId: String, val inputTokens: Int?, val outputTokens: Int?, val latencyMs: Long)
```
Implementations:
- `AnthropicLlmClient` — LangChain4J Anthropic, model id from config.
- `OpenAiCompatibleLlmClient` — base URL + model from config. This covers Ollama, LM Studio and llama.cpp server running locally (target: gpt-oss-20b and Qwen3.6-27B on RTX 4070 Ti Super, 16 GB VRAM). It may reuse the existing OpenAI code path.

Clients are named beans: the DMN track and the test track each pick one by name from config.

### 4.2 `generation` — the two tracks
- `DmnGenerator(client, promptTemplate, contextProvider)` — produces DMN XML (strips code fences, normalises whitespace as today).
- `TestSuiteGenerator(client, promptTemplate, contextProvider)` — produces a `TestSuite` (JSON array as today).
- The two generators share **no** instances: each has its own `LlmClient` and its own optional RAG provider. Neither may read the other's output (enforce with an architecture test or package visibility).
- Prompts move out of annotations into versioned resource files `resources/prompts/{dmn,tests}/v1.txt`. Log the prompt id/hash.

### 4.3 `context` — schema and RAG
- `DomainRegistry` replaces the `if` chains (F12): domain id → schema, sample data, optional RAG corpus.
- RAG is an explicit option: `rag: { enabled, corpus: [...], k, minScore }`. It is logged per run.

### 4.4 `validation`
- `DmnParser` (Camunda model + parse), `DmnExecutor`, `Validator` interface with `LenientValidator` / `StrictValidator` (section 3).
- `SemanticHash` — a hash of the normalised table: hit policy, input expressions, and per rule the sorted (inputExpression, entry) pairs plus outputs, with labels and whitespace ignored. Used to tell whether a retry changed the logic (finding: all 221 tables in the 2025-10-31 log had identical logic).

### 4.5 `orchestration` — the Dispatcher
```kotlin
enum class RepairStrategy { STATIC_ORACLE, PAIRED_REGEN, ESCALATE_ON_STABLE_DISAGREEMENT }
```
- `STATIC_ORACLE` (A): tests are generated once per cycle; only the DMN is regenerated on failure.
- `PAIRED_REGEN` (B): both tracks are regenerated on failure (current behaviour).
- `ESCALATE_ON_STABLE_DISAGREEMENT` (V3): if the semantic hash of the DMN is unchanged for `k` attempts **and** the failing test inputs fall in the same region, stop and return `AMBIGUOUS` with the conflicting cases instead of regenerating the oracle.
- The retry budget is configurable (default 3; the current code uses 10).
- Cycle outcome: `ACCEPTED`, `REJECTED_BUDGET`, `AMBIGUOUS`, `ERROR`.

### 4.6 `experiment` — runner, out of the test class
- A Spring profile `experiment` with an `ApplicationRunner` (or a separate main), driven by a run config file `experiments/<name>.json`:
```json
{
  "runId": "fop-e2-cross-001",
  "ruleSet": "fop", "version": "2026",
  "dmnModel": "anthropic:<exact-model-id>", "testModel": "local:gpt-oss-20b",
  "validator": "STRICT", "strategy": "PAIRED_REGEN", "retryBudget": 3,
  "rag": { "enabled": false },
  "cycles": 100, "temperature": 0.7, "seed": 42
}
```
- Ground-truth evaluation is separate from the in-loop oracle. After a cycle is accepted, the final DMN is evaluated against the human ground-truth cases of the rule set and logged.
- Runs must be resumable: skip cycle indices already present in the JSONL.

### 4.7 `mutation` — for E1
`DmnMutator` operators over a ground-truth DMN, each producing a mutant together with the input region it affects:
`THRESHOLD_SHIFT` (±1 unit / ±1%), `BOUNDARY_FLIP` (`<`↔`<=`, `[`↔`(`), `RULE_SWAP` (adjacent rules under FIRST), `RULE_DROP` (incl. default rule), `OUTPUT_SWAP`, `CONDITION_DROP`, `HIT_POLICY_CHANGE`.
Kill = at least one test case in the suite fails under strict validation.

### 4.8 Rule sets on disk
```
resources/rulesets/<domain>/<version>/
    rules.uk.txt        # source text (Ukrainian)
    rules.en.txt        # parallel English text
    meta.json           # hitPolicy, schema ref, source citation, ambiguous: true/false
    ground-truth.json   # human-labelled cases: input object + expected (null = no rule applies)
    reference.dmn       # human reference table (for E1)
```
The mortgage rule set moves here from the test class as `mortgage/ambiguous` and `mortgage/disambiguated`. The ФОП rule set (`fop/2024`, `fop/2025`, `fop/2026`) is drafted in Cowork and delivered into this structure.

## 5. JSONL event log

One file per run: `runs/<runId>.jsonl`. One JSON object per line:

- `run_started` — full run config, git commit, model ids, prompt hashes, rule set hash.
- `attempt` — cycle, attempt, track timings, tokens per track, DMN semantic hash, test suite size, the per-test outcome list (section 3), and counts per outcome.
- `cycle_finished` — outcome, attempts, ground-truth results (per case: expected, actual, outcome), cost estimate.
- `run_finished` — aggregates.

Store the full DMN XML and the test-suite JSON in the `attempt` event (or as side files referenced by hash) so every analysis can be redone without new LLM calls.

## 6. Work order and acceptance criteria

1. **Hygiene.** Delete the log4j deps (F10), move secrets to env vars, switch to constructor injection in the touched classes. *Done when:* the build is green and the app starts with keys from env only.
2. **Validators.** `LenientValidator` + `StrictValidator` + unit tests from section 3. *Done when:* the F1 scenario is `PASS` under lenient and `EMPTY_RESULT` under strict.
3. **LLM abstraction.** `LlmClient`, Anthropic + OpenAI-compatible clients. *Done when:* a smoke test generates one test suite through a local Ollama/LM Studio endpoint and one DMN through Anthropic in the same run.
4. **Split tracks.** `DmnGenerator` / `TestSuiteGenerator` with separate clients, prompts in resources; remove `generateTestCases` from `DmnValidator` (F4). *Done when:* there are no shared instances between the tracks (an architecture test checks this).
5. **Dispatcher** with the three strategies and `SemanticHash`. *Done when:* unit tests with a fake `LlmClient` cover A, B and V3, including V3 returning `AMBIGUOUS`.
6. **Experiment runner + JSONL.** *Done when:* a 3-cycle mortgage run produces a valid JSONL that contains every field from section 5.
7. **V0 reproduction check.** Mortgage, `LenientValidator`, `PAIRED_REGEN`, model `CLAUDE_SONNET_4_20250514`, no RAG, 20 cycles. *Done when:* the outcome distribution is qualitatively in line with the 2025-10-31 log (same table logic, retries with unchanged semantic hash).
8. **Mutator** (E1). *Done when:* every operator has a unit test on `mortgage/reference.dmn`.
9. **ФОП rule set** wired in (files delivered from Cowork). *Done when:* `fop/2026` passes a 3-cycle smoke run.

Steps 1–6 are a prerequisite for any new numbers. Steps 7–9 can then run in parallel.

## 7. Out of scope for this revision

- JavaScript or another expression language instead of FEEL (separate future paper).
- Multi-decision DRDs.
- UI and Swagger polish, and upgrading Spring Boot or Kotlin (unless a dependency forces it).
- The other legacy domains (education, vap, hotel, bmi, users): keep them compiling, do not migrate them.

## 8. Open questions for Vlad

- The exact Anthropic model id for the new runs (latest available at run time; it must be pinned and logged).
- RAG: re-enable it with a defined corpus as a factor, or declare the new experiments RAG-free (simpler, and honest given F7)?
- Is it OK to make the repository public later (or publish a sanitized snapshot) for the EN paper's reproducibility section? The `TorqueITS` org ownership matters here.
