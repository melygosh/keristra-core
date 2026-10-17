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

**Pre-flight status (2026-10-17): done.** Keys rotated by Vlad. The unpushed history (`origin/main..`) was rewritten with `git filter-branch --index-filter`: in `application.properties` of the six affected commits the key values were replaced by `${ANTHROPIC_API_KEY:}` / `${OPENAI_API_KEY:}` / `${VOYAGE_API_KEY:}`, so V0 at the tag still starts with keys from env. Nothing else changed (same tree at the branch head, same messages, authors, dates); a scan of every file in every reachable commit finds none of the old values. All commit hashes from `9ad9960` on changed — old hashes in this document, in earlier commit messages and in `runs/mortgage-smoke-001.jsonl` (`gitCommit`) map as follows:

| before | after | commit |
|---|---|---|
| `9ad9960` | `bb7b1da` | PoC: add VAP case |
| `a278417` | `07ef55e` | PoC: refactor |
| `0ae3502` | `143c1a3` | PoC: add validation |
| `b2da9d1` | `ffaf6cc` | PoC: add education |
| `5079be2` | `badbc85` | PoC: add mortgage |
| `bc0c63d` | `843f4b9` | WIP commit = `main` = tag `v0-dissertation` |
| `756a323` | `a9e7d17` | step 1, hygiene |
| `376319c` | `89168a4` | step 2, validators |
| `7c56cf4` | `48b48e2` | step 3, LLM clients |
| `973da15` | `dc54f4a` | step 4, generation tracks |
| `dbc39da` | `69bc12b` | step 5, dispatcher (the `gitCommit` of `runs/mortgage-smoke-001`) |
| `1769d9f` | `1254cf7` | step 6, experiment runner |
| `c60d410` | `9a56702` | step 8, mutator |

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
| F14 | `validation/DmnValidator.kt` `isValidDMN()` | If the DMN fails to parse, `validate()` returns `null` and `isValidDMN()` maps it to an empty error list, so the controller **accepts** the unparseable table. Found while implementing step 2; reproduced by `LenientValidatorTest`. | Malformed tables could be counted as successes in V0 runs; check how often generation produced unparseable XML in the logs before quoting V0 success rates. |
| F15 | Anthropic API, 2026-10-16 | `claude-sonnet-4-20250514` (the V0 model, F8) now returns `not_found_error`. Found by the step 3 smoke run. | Step 7 cannot rerun V0 on its own model. Closest still-served models that accept sampling parameters: `claude-sonnet-4-5-20250929`, `claude-sonnet-4-6`. The paper has to say the V0 numbers are not reproducible on the original model. |
| F16 | Anthropic API, 2026-10-16 | Current models (Sonnet 5.5, Opus 5.5, Haiku 5.5, Sonnet 5, Opus 5) reject non-default `temperature`/`top_p`/`top_k` with a 400, and run adaptive thinking by default (it counts towards `max_tokens`; depth is set by `output_config.effort`). | `"temperature": 0.7` in the run config (section 4.6) only applies to local models and Claude ≤ 4.6. For current Claude models the sampling factor is effort, not temperature — log it. `LlmClient` sends exactly what it is given and does not strip parameters silently. |
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
- Decided in step 5 (Claude Code, 2026-10-16; revisit for the paper):
  - **Region** of a failing case = its disagreement signature: outcome + expected + actual. V3 escalates when the semantic hash is identical for `k` consecutive attempts (default `k = 2`) and all of them share at least one signature; the conflicting cases returned are the latest attempt's failures in those shared regions.
  - **V3 regenerates both tracks** (like B). Only independent oracles that keep disagreeing with the same logic in the same way point at ambiguous rule text; with a fixed oracle (A) a repeated disagreement would only measure the DMN generator's stubbornness.
  - A test suite that does not parse fails the attempt without generating a DMN (V0 order: tests first); under A the oracle is the first suite that parses. Any other track exception (transport, auth, `not_found`) ends the cycle with `ERROR`.
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
- As implemented in step 6 (Claude Code, 2026-10-16):
  - Sampling is **per track** because of F16 — the flat `temperature`/`seed` above cannot express "no temperature for Claude, 0.7 for the local model":
    ```json
    "dmn":   {"model": "anthropic:claude-sonnet-5-5", "prompt": "v1", "maxTokens": 16000},
    "tests": {"model": "local:gemma4:26b", "prompt": "v1", "temperature": 0.7, "seed": 42, "maxTokens": 8000}
    ```
    plus `"language"` (which `rules.<lang>.txt` to use) and `"stableAttempts"` (V3's `k`). Unknown fields are rejected. Example: `experiments/mortgage-smoke-001.json`.
  - The effective seed per call is `seed + cycle * 1000 + attempt`; a fixed seed would make a local model repeat itself in every cycle and retry.
  - Run: `java -jar build/libs/keristra-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=experiment --keristra.experiment.config=experiments/<name>.json` (output `runs/<runId>.jsonl`; `--keristra.experiment.output-dir` to change).
  - Ground truth is checked with the strict validator for **every** cycle that produced a DMN, not only accepted ones, so `run_finished` counts false accepts (`acceptedWrong`) and false rejects (`rejectedCorrect`).
  - Resume: rerunning a runId skips cycles with a `cycle_finished`, except cycles whose latest outcome is `ERROR` (infrastructure failure, not a result: they run again); the config must match except `cycles`. Every event has a `sessionId`; attempts of a cycle that never finished are superseded by its rerun.
  - Costs come from `keristra.pricing.*` (USD/MTok, list prices as of 2026-10-06); local providers cost 0; unknown prices give `costUsd: null`.

### 4.7 `mutation` — for E1
`DmnMutator` operators over a ground-truth DMN, each producing a mutant together with the input region it affects:
`THRESHOLD_SHIFT` (±1 unit / ±1%), `BOUNDARY_FLIP` (`<`↔`<=`, `[`↔`(`), `RULE_SWAP` (adjacent rules under FIRST), `RULE_DROP` (incl. default rule), `OUTPUT_SWAP`, `CONDITION_DROP`, `HIT_POLICY_CHANGE`.
Kill = at least one test case in the suite fails under strict validation.
- As implemented in step 8 (Claude Code, 2026-10-17): `mutation.DmnMutator` (first-order mutants, one change each, `MutationRegion` = rule ids + input/output column + entry before/after) and `MutationAnalysis.isKilled`. THRESHOLD_SHIFT is ±1 for integer literals and ±1% for decimals; BOUNDARY_FLIP covers comparison operators and each range bracket; RULE_SWAP applies under FIRST and RULE ORDER; OUTPUT_SWAP uses the other values of the same output column; HIT_POLICY_CHANGE targets UNIQUE, FIRST, ANY, COLLECT, RULE ORDER. Text inside FEEL string literals is never mutated. Equivalent mutants occur (e.g. swapping rules 1/2, both `Fixed30`) and must be handled in the E1 analysis.
- *(Superseded 2026-10-17, see step 11: `reference.dmn` is now the human reference; the draft below is kept as `reference.draft-sonnet.dmn`.)* **`mortgage/ambiguous/reference.dmn` was a DRAFT** (marked in `meta.json`): the DMN generated in `runs/mortgage-smoke-001` (claude-sonnet-5-5, semantic hash `2b2d439296c9`), which passes the ground truth 8/8. To review: it keeps the rule-text order, so a fixed preference or high rate sensitivity wins over the ManualReview risk checks (credit < 640, DTI > 50, LTV > 95), and it has no default rule (ground-truth case 8 expects no result).
- First data point for E1 (draft reference, 54 mutants): the 8 human ground-truth cases kill 29/54 — RULE_DROP 6/6, OUTPUT_SWAP 12/12, HIT_POLICY_CHANGE 4/4, CONDITION_DROP 5/9, THRESHOLD_SHIFT 2/12, BOUNDARY_FLIP 0/6, RULE_SWAP 0/5. The human cases barely probe boundaries.

### 4.8 Rule sets on disk
```
resources/rulesets/<domain>/<version>/
    rules.uk.txt        # source text (Ukrainian)
    rules.en.txt        # parallel English text
    meta.json           # hitPolicy, schema ref, source citation, ambiguous: true/false
    ground-truth.json   # human-labelled cases: input object + expected (null = no rule applies)
    reference.dmn       # human reference table (for E1)
```
The mortgage rule set moves here from the test class as `mortgage/ambiguous` and `mortgage/disambiguated`. Done in step 6 for `mortgage/ambiguous` only (V0 text verbatim, English only; ground truth converted from `src/test/resources/mortgage-test.json`): **`mortgage/disambiguated` does not exist yet** — its text and ground truth are research content to write. `meta.json` names the `domain`; the schema comes from `DomainRegistry`, which reads schemas as classpath resources (V0's `SchemaResolver` read files and failed inside the jar; fixed too). The ФОП rule set (`fop/2024`, `fop/2025`, `fop/2026`) is drafted in Cowork and delivered into this structure.

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

- The exact Anthropic model id for the new runs (latest available at run time; it must be pinned and logged). See F15/F16: a current model means no temperature control (effort instead); a ≤ 4.6 model keeps temperature but is closer to retirement.
- V0 reproduction (step 7) after F15: rerun on `claude-sonnet-4-5-20250929` as the nearest substitute, or drop step 7 and report V0 numbers as historical only?
- RAG: re-enable it with a defined corpus as a factor, or declare the new experiments RAG-free (simpler, and honest given F7)?
- Is it OK to make the repository public later (or publish a sanitized snapshot) for the EN paper's reproducibility section? The `TorqueITS` org ownership matters here.

## 9. Additions after the smoke run (2026-10-17)

### Finding F17 — the oracle still yields under the strict validator
*(Numbered F14 by Cowork; renumbered because F14 already names the unparseable-table acceptance in section 2.)*
`runs/mortgage-smoke-001.jsonl`, session `bb34…`, cycle 2:
- Attempt 1: the gemma4:26b test suite has case 6 (DTI 51, ARM conditions met), which expects `ManualReview`. The Sonnet 5.5 table returns `ARM7/1`, so the result is `MISMATCH`.
- Attempt 2: the DMN semantic hash is unchanged (`2b2d4392`), the tests were regenerated without that case, and the cycle was `ACCEPTED`.
- The old 8-case ground truth did not cover this region, so the run reported `acceptedCorrect` 3/3.

On the extended ground truth (below), this same table (`reference.draft-sonnet.dmn`) fails 8/45: every knockout-vs-product conflict.

The current V3 does not catch it either: `stableDisagreement` requires a shared failure region in consecutive attempts, and a resampled oracle that simply omits the conflicting input erases it.

### Step 10 — conflict memory in the Dispatcher (all strategies that resample tests)
- Keep a per-cycle **open-conflict set**: every failing case (input object + expected + actual + DMN semantic hash) observed in any attempt.
- On every later attempt, re-run the open conflicts against the new DMN, in addition to the fresh test suite.
- A conflict is **closed** only when one of these holds:
  - (a) the DMN semantic hash changed and the case now passes, or
  - (b) an explicit re-adjudication retracts the old expectation: the test track receives the conflicting input alone, with the rule text and without the DMN, and returns a different expected output. Log it as `oracle_retraction`.
- A cycle cannot be `ACCEPTED` while it has open conflicts.
  - Under `PAIRED_REGEN` with conflict memory, an exhausted budget with open conflicts gives `REJECTED_BUDGET`.
  - Under V3, open conflicts plus an unchanged semantic hash for `stableAttempts` attempts give `AMBIGUOUS`, with the open conflicts attached.
- Keep the current behaviour available as an explicit flag (`conflictMemory: false`). The article needs both arms: with and without memory.
- New JSONL fields:
  - in `attempt`: `openConflicts`, `closedConflicts` (each with its close reason);
  - in `cycle_finished`: `openConflictsAtEnd`.
- Unit tests with `FakeLlmClient`:
  - DMN unchanged, the oracle drops the case → not accepted;
  - DMN fixed → conflict closed by (a);
  - retraction → conflict closed by (b).

**Status (Claude Code, 2026-10-17): done.** `DispatcherConfig.conflictMemory` / run config `"conflictMemory": true` (default `false` = the arm without memory). Details decided in implementation:
- Memory applies to PAIRED_REGEN and V3; under STATIC_ORACLE the flag has no effect (the fixed oracle already keeps every expectation).
- A conflict is identified by the canonical JSON of its input plus its expectation (key order ignored), so the same disagreement from several oracles is one conflict.
- Re-adjudication (b) is asked only when an attempt would otherwise be accepted (its fresh suite passes) and at most once per conflict per cycle, to bound calls to slow local models. Prompt: `prompts/tests-adjudicate/v1.txt`, sent by the test track with the rules, schema and the single input, never the DMN.
- (b) closes the conflict only if the new expectation **agrees with the current DMN**, not merely differs from the old one; a changed verdict that still disagrees keeps it open.
- An infrastructure error during re-adjudication ends the cycle with `ERROR`; an unparseable verdict is logged and the conflict stays open.
- JSONL: `attempt.accepted` is now the memory-aware verdict, `attempt.suiteAccepted` the fresh suite alone; `openConflicts`, `closedConflicts` (with `reason` DMN_FIXED / ORACLE_RETRACTION), `adjudications` (with the call's tokens, latency and cost); `cycle_finished.openConflictsAtEnd`; adjudication calls count towards the test track's tokens and cost. Logs written before the flag existed still resume (missing fields compare at their defaults).
- Unit tests reproduce F17 in both arms: without memory the unchanged table is accepted once the oracle drops the case; with memory it is not.

### Step 11 — the mutation test fixture after the ground-truth change
`mortgage/ambiguous/ground-truth.json` now has 45 cases (8 original plus conflicts, boundaries, nulls and near-misses; see `category`), and `reference.dmn` is a human reference with rule order knockouts → Fixed30 → ARM7/1. The Sonnet draft moved to `reference.draft-sonnet.dmn`.

`DmnMutatorTest` is written against the draft (rule ids and order, mutant counts, which mutants are killed, line 42 `accepted`), so it **will fail** until it is updated:
- point the structural assertions at the new `reference.dmn` and recompute the counts;
- assert that the new reference passes 45/45 under `StrictValidator`;
- add a test that the draft fails exactly the 8 conflict cases (ids 9–16).

The 45/45 result was checked only with a Python re-implementation of the FEEL subset (Maven Central was unreachable from Cowork). This step is the real Camunda confirmation.

**Status (Claude Code, 2026-10-17): done.** Confirmed with the Camunda-based `StrictValidator`: both `reference.dmn` files pass 45/45; the Sonnet draft fails exactly ids 9–16, all `conflict:knockout-vs-*`. `DmnMutatorTest` and `RuleSetRepositoryTest` now target the human reference, the 45 cases, the `uk` texts and the disambiguated variant. Mutation data point on the human reference (54 mutants): the 45 cases kill 51/54 — the 3 survivors are the equivalent rule swaps (two knockouts, or the two Fixed30 rules) — while the original 8 cases kill 29/54.

### Step 12 — rule-set variants for E3
`mortgage/disambiguated/` added: explicit priority, inclusive/exclusive boundaries, null handling, no default. Ground truth and reference DMN are identical to `mortgage/ambiguous`, so only the text differs.

Both versions now have `rules.uk.txt` next to `rules.en.txt`. Language becomes a possible factor; the UA article can use `uk`.

### Step 13 — ФОП rule sets (delivered 2026-10-17 from Cowork)
There are two decisions. Each has three versions with the same input objects, so E4 can evaluate the table of one version against the tests of another:

| Rule set | Versions | Cases | Outputs | What changes between versions |
|---|---|---|---|---|
| `fop-eligibility` | 2024, 2025, 2026 | 38 | `eligible`, `reason` | Income limits only (167/834/1167 × minimum wage on 1 January: 7 100 / 8 000 / 8 647) |
| `fop-obligations` | 2024, 2025, 2026 | 12 | `singleTax` (+ `militaryLevy` from 2025) | 2024→2025 is **structural** (the military levy output appears); 2025→2026 is parametric (subsistence minimum 3 028→3 328, minimum wage 8 000→8 647) |

- Schema: `schema/fop-schema.json` (root `fop`). The domain `fop` is registered in `DomainRegistry.V0_SCHEMAS` (one line, added from Cowork — please compile).
- Texts: `rules.uk.txt` (primary, for the UA article) and `rules.en.txt`. The 2024 text deliberately mentions the 1 April 2024 minimum wage (UAH 8 000) as a distractor, without saying it is irrelevant: the rule says limits and rates use the 1 January value, so they must be (7 100 → limits 1 185 700 / 5 921 400 / 8 285 700, group 2 tax 1 420).
- Reference DMNs pass their own ground truth 38/38 and 12/12, checked with the Python FEEL-subset re-implementation. **Confirm with Camunda**, especially:
  - `not("market_retail","household_services")`;
  - `decimal(fop.monthlyIncome * 0.05, 2)`, which the reference uses as output expressions; the check assumes `decimal()` rounds HALF_EVEN, and the ground-truth values avoid ties;
  - integer comparison of `requestedGroup` against JSON numbers parsed as doubles (`1` vs `1.0`).
- Expected cross-version failures of the references (rows: DMN version, columns: ground-truth version), useful as the E4 sanity baseline:
  - eligibility: 2024→{2025: 6, 2026: 12}, 2025→{2024: 6, 2026: 6}, 2026→{2024: 12, 2025: 6};
  - obligations: 2024→{2025: 11, 2026: 11}, 2025→{2024: 2, 2026: 5}, 2026→{2024: 5, 2025: 5}.
- Known validator limitation exposed by E4: only expected keys are compared, so a 2025+ table evaluated against 2024 tests does not flag the extra `militaryLevy` output. Decide whether `StrictValidator` should report unexpected output keys (e.g. an `EXTRA_OUTPUT` outcome) before running E4. *(Done — see the status below.)*
- Not modelled yet (possible later versions): 15% on income above the limit and the forced group change; exemptions for mobilised FOPs; VAT changes for FOPs from 2027, if they are adopted.

Generators: `tools/rulesets/gen_fop.py` (texts, ground truth, reference DMNs; parameters in `fop_params.py`), `tools/rulesets/check_fop.py` (FEEL-subset check and the cross-version matrix). Edit the generator, not the generated files.

**Status (Claude Code, 2026-10-17): confirmed on Camunda and committed.**
- All six references pass their own ground truth (38/38, 12/12) with the schema check on; `not(...)`, `decimal(..., 2)` and `requestedGroup` 1 vs 1.0 behave as assumed. `en` and `uk` share one ground truth per version. Pinned in `FopRuleSetsTest`.
- **Decided: `StrictValidator` reports `EXTRA_OUTPUT`** — a non-null result output the case does not name (MISMATCH on an expected key takes precedence; `CaseResult.extraOutputs` lists the keys). The E4 baseline matrix (failing cases, DMN version → ground-truth version) is now:

| DMN \ GT | eligibility 2024 | 2025 | 2026 | obligations 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|
| 2024 | — | 6 | 12 | — | 11 | 11 |
| 2025 | 6 | — | 6 | **11** (9 EXTRA_OUTPUT + 2) | — | 5 |
| 2026 | 12 | 6 | — | **11** (6 EXTRA_OUTPUT + 5) | 5 | — |

  Eligibility differences are all MISMATCH (income limits). For obligations the 2024→2025 structural change is now visible in both directions; before EXTRA_OUTPUT, 2025→2024 and 2026→2024 showed only 2 and 5. The 12th obligations case (no group selected) passes everywhere.
- Side effect to keep in mind for in-loop runs: an LLM oracle that names only some outputs of a multi-output table (e.g. only `eligible`, not `reason`) now fails with EXTRA_OUTPUT. The prompts may need to ask for every output explicitly.
- `check_fop.py` still compares expected keys only, so its matrix for obligations 2025/2026→2024 shows the old 2 and 5; `FopRuleSetsTest` is the authoritative check.


### Step 14 — RAG back as an explicit factor (decided with Vlad, 2026-10-17)
Why it was off: Voyage AI access was blocked (IP), so `loadDocuments` was dropped and nothing replaced it — an infrastructure accident, not a design decision. The new experiments are self-contained: RAG is a logged factor of the new runs, and the article does not reuse numbers from the 2025-10 run as RAG results.
- **Corpus.** Keep the V0 corpus, frozen and hashed: `DMNCustom.txt`, `DMNCustom2.txt`, `DMN.txt`, `DMNSpec.xsd`, and `data-set/tuning.jsonl` if it is used. Checked from Cowork: it holds DMN/FEEL syntax patterns and examples from the `users` domain; there are no mortgage or ФОП terms, so no leakage of the evaluated rule sets. Log the corpus hash in `run_started`.
- **Embeddings run locally, without Voyage.** Use an OpenAI-compatible embeddings endpoint on the same Ollama/LM Studio host. The rule texts are also Ukrainian, so pick a multilingual model (e.g. `bge-m3` or multilingual-e5 — check what the local server offers). The current `AllMiniLmL6V2` is English-only.
- **Threshold.** `minScore 0.8` with MiniLM likely retrieves nothing. Calibrate it on the rule texts, and log the retrieved segment ids and scores per call in the `attempt` event. Otherwise "RAG on" cannot be told apart from "RAG on but empty".
- **RAG on the DMN track only.** The corpus is about DMN/FEEL syntax, which the test track does not need. Shared retrieval would also feed both tracks the same context and correlate their errors, which works against the independence claim. Make this explicit in the run config: `dmn.rag` and `tests.rag`, with `tests.rag` off by default.
- **Scope.** One extra arm, not a full crossing: the best mortgage E3 configuration with `dmn.rag` off vs on. A full RAG factor belongs in the EN paper, if anywhere.

**Status (Claude Code, 2026-10-17): implemented, not yet run on real embeddings.**
- Config: `"dmn": {..., "rag": {"enabled": true, "embeddingModel": "local:bge-m3", "k": 6, "minScore": 0.0, "corpus": [...], "segmentSize": 1024, "segmentOverlap": 128}}`. `corpus` defaults to the V0 files (`DMNCustom.txt`, `DMNCustom2.txt`, `DMN.txt`, `DMNSpec.xsd`, `data-set/tuning.jsonl`). `tests.rag.enabled` and the old top-level `rag.enabled` are rejected. The embeddings come from an OpenAI-compatible `/embeddings` endpoint through the same `keristra.llm.providers.*` (Anthropic has none).
- Query = the rules text; the segments are appended to the DMN user message the way LangChain4J's `DefaultContentInjector` did in V0 (`"\n\nAnswer using the following information:\n"` + segments). The corpus is embedded once per run. The prompt id stays `dmn/v1`; RAG is visible in the log, not in the prompt id.
- Logged: `run_started.rag.dmn` (embedding model, corpus files, corpus hash over the files, segment count, segmentation, k, minScore, score definition) and `attempt.tracks.dmn.rag` (count plus segment ids and scores; `null` = RAG off, `count: 0` = on but empty).
- Scores are LangChain4J relevance scores, `(cosine + 1) / 2`, so `minScore` is on that scale, not raw cosine. The default `minScore` is 0.0 (always top-k) until it is calibrated.
- **Calibration (2026-10-17, `local:bge-m3` on Ollama, V0 corpus = 139 segments of 1024/128 chars, corpus hash `d7d7974a…`, embedded in ~21 s).** Top-6 relevance scores, query = rules text:

| Queries | Top-6 range |
|---|---|
| mortgage ambiguous/disambiguated, en/uk (4) | 0.776 – 0.822 |
| ФОП eligibility/obligations 2024/2026, uk/en (8) | 0.723 – 0.772 |
| Unrelated controls: recipe en/uk, weather, football, poem (5) | 0.677 – 0.711 |

  - **Use `minScore: 0.72` with `k: 6` for `bge-m3`.** Every rule text keeps its full top-6 and every control query is cut. The margin is thin (0.723 vs 0.711), so log and check `attempt.tracks.dmn.rag.count` in the runs. The code default stays 0.0, because the threshold is model-specific.
  - V0's `minScore 0.8` would give every ФОП text **zero** segments and mortgage 0–4, the "RAG on but empty" case.
  - Retrieval barely depends on the domain: every rule text gets mostly the same general DMN text (`DMN.txt#3`, `#4`, `#22`, `#25`, `#0`). In effect RAG is a fixed DMN/FEEL syntax primer, not domain knowledge. This matters for how the article describes the RAG arm.

### tests/v2 — every output in every expectation (Claude Code, 2026-10-17)
Follows from `EXTRA_OUTPUT` (step 13 status). `prompts/tests/v2.txt` and `prompts/tests-adjudicate/v2.txt` are v1 plus one requirement in the system message: every expected object names every output variable of the rules, and `[]` stands for "no rule applies". The user message is unchanged, so v1→v2 is a single, clean factor; v1 stays the V0 prompt. The adjudication prompt now follows the test track's prompt version (`tests.prompt: "v2"` → `tests-adjudicate/v2`).

