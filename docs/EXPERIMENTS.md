# Experiment protocol — independent dual-track generation of DMN tables

Status: v1, written 2026-10-17 in Cowork, **before** any experimental run. Changes after the first E3 run must be logged in section 9 with date and reason. Nothing in sections 2–6 may be changed after looking at E3 results.

## 1. Claim under test

The main contribution is **two independent generations from the same source text** — the DMN logic and a test suite — **and their mutual verification**. The experiments test three things:

- whether the test track really detects wrong logic (E1);
- whether the closed loop can *accept* wrong logic, and whether conflict memory prevents it (E3);
- whether disagreement between the tracks can signal an ambiguous rule text instead of being suppressed (E3).

E2 (same vs different model on the tracks) and E4 (regression across rule versions) extend this for the EN paper.

## 2. Fixed setup (identical for every arm unless the arm says otherwise)

| Item | Value |
|---|---|
| Code | branch `revision/experiment-core`; the exact commit is logged in `run_started.gitCommit`. No run on a dirty tree. |
| DMN track | `anthropic:claude-sonnet-5-5`, prompt `dmn/v1`, default effort; temperature is not settable (F16). |
| Test track | the local model chosen in section 3 (C0), prompt `tests/v2`, temperature 0.7, seed base 42. |
| Validator | `STRICT` (with `EXTRA_OUTPUT`). |
| Retry budget | 3 attempts per cycle; `stableAttempts` 2 for V3. |
| RAG | off, except arm R (section 5). |
| Rule-text language | `uk` (primary: Ukrainian rule texts, matching the UA paper and the real ФОП regulation). One control arm in `en` (L1). |
| Ground truth | `mortgage/{ambiguous,disambiguated}`: 45 cases, human reference DMN; ФОП: 38 / 12 cases per version. |

## 3. C0 — calibration of the local test model (not reported as a result)

- **Candidates:** `local:gemma4:26b`, `local:gpt-oss-20b`.
- **Task:** 10 test suites per candidate for `mortgage/ambiguous` (`uk`, `tests/v2`). No DMN generation.
- **Measures, per candidate:**
  - mutation score on the human reference (definition in section 4.1);
  - share of failed calls (timeouts, unparseable output);
  - median latency per suite;
  - median suite size;
  - share of suites with an `EXTRA_OUTPUT` or schema error.
- **Decision rule, fixed now:**
  1. Drop a candidate with more than 20% failed calls.
  2. Of the rest, take the higher median mutation score.
  3. If the medians are within 0.05, take the faster model.
- Record the decision in section 9 before E3 starts.

## 4. Metrics

### 4.1 Mutation score (E1)
- Mutants come from `DmnMutator` (7 operators) on a human reference DMN. **Equivalent mutants are excluded**, i.e. mutants with the same behaviour as the reference over the input domain:
  - on `mortgage`: the 3 known rule swaps (two knockouts, or the two Fixed30 rules);
  - on other references: mutants that survive all 45/38/12 ground-truth cases *and* a dense input grid are checked by hand and listed in section 9.
- Kill: at least one case of the suite fails under `StrictValidator`.
- **MS(suite) = killed / non-equivalent mutants.** Report it per suite (distribution), per generator (median, IQR) and per operator.

### 4.2 Cycle-level metrics (E3/E2)
For every cycle outcome other than `ERROR`, the final DMN is evaluated on the full ground truth. A table is **GT-correct** when it passes all cases.

| Metric | Definition |
|---|---|
| **FAR** (false acceptance rate) — primary | accepted ∧ GT-wrong / valid cycles |
| Precision of acceptance | GT-correct / accepted |
| Detection rate | not accepted / cycles whose first-attempt DMN is GT-wrong |
| False rejection | not accepted ∧ final DMN GT-correct / valid cycles |
| Oracle-yield events | attempts with an unchanged DMN semantic hash where a previously failing input is absent from the new suite and the attempt is accepted (memory-off arms); with memory on: conflicts closed by `ORACLE_RETRACTION` |
| AMBIGUOUS rate | `AMBIGUOUS` / valid cycles (V3 arms) |
| Cost | tokens per track, USD, latency, attempts per cycle |

`ERROR` cycles are infrastructure failures. They are excluded from the denominators and re-run by resuming until each arm has n valid cycles. Their counts are reported.

## 5. Arms

### E1 — test-track detection power (UA + EN)
| Arm | Test source | Suites |
|---|---|---|
| E1-H45 | human ground truth (45) | 1 |
| E1-H8 | original 8 cases | 1 |
| E1-S2 | `anthropic:claude-sonnet-5-5`, `tests/v2` | 10 (the C0 suites) |
| E1-L2 | gemma4:26b, `tests/v2` | 10 (the C0 suites) |
| E1-L3 | gemma4:26b, `tests/v3` | 10 |
| E1-S3 | Sonnet 5.5, `tests/v3` | 10 |

Run on `mortgage/ambiguous` reference (`uk`). The EN paper adds the six ФОП references.

### E3 — closed loop on mortgage (UA core)
Text ∈ {ambiguous, disambiguated} × the arms below, **n = 50 valid cycles each**:

| Arm | Strategy | Conflict memory |
|---|---|---|
| B0 | PAIRED_REGEN | off (dissertation behaviour on the new validator) |
| B1 | PAIRED_REGEN | on |
| V1 | ESCALATE_ON_STABLE_DISAGREEMENT | on |

That gives 6 arm-text combinations, 300 cycles.

Extra arms, 50 cycles each, ambiguous text only:
- **R** — the better of B1/V1 with `dmn.rag` on (`local:bge-m3`, k 6, minScore 0.72, V0 corpus).
- **L1** — B1 with `en` texts (language control).

### E2 — same vs different model on the tracks (EN)
- **S1** — B1 with the test track on `anthropic:claude-sonnet-5-5`.
- Both texts, n = 50 each.
- Compared with B1, which has the local model on the test track.

### E4 — regression across ФОП versions (EN)
This is a suite-only experiment, with no DMN generation:
- Generate 10 suites per version and decision from the text of version N+1 (C0 model, `uk`).
- Evaluate the **reference** DMN of version N on them, and the other direction as well.
- Detection = share of the changed-region ground-truth cases (`boundary:limit-*` for eligibility, all cases for obligations 2024↔2025) on which the suite contains at least one failing case covering the same region. The simpler headline is the share of suites that reject the stale reference.
- The sanity baseline is the pinned matrix in REVISION_PLAN step 13.

## 6. Hypotheses and analysis (fixed before E3)

| ID | Hypothesis | Test |
|---|---|---|
| H1 | MS of the generated suites (E1-S2, E1-L2, E1-S3, E1-L3) is below MS(E1-H45) and above MS(E1-H8); v3 ≥ v2 per model (see section 9) | Descriptive with bootstrap 95% CI of the median; no significance claim |
| H2 | On the ambiguous text, FAR(B0) > FAR(B1) | One-sided Fisher exact test, α = 0.05 |
| H3 | AMBIGUOUS rate in V1 is higher on the ambiguous text than on the disambiguated one | One-sided Fisher exact test, α = 0.05 |
| H4 | On the disambiguated text, FAR is low in every arm (control) | Wilson 95% CI reported; no test |
| X1 | Share of conflicts closed by `ORACLE_RETRACTION` vs `DMN_FIXED` | Exploratory, reported as is |
| X2 | Effect of RAG (R vs its base arm), language (L1 vs B1), model pairing (S1 vs B1) | Exploratory: Fisher two-sided, reported with CIs, no correction claims |

- All proportions are reported with Wilson 95% CIs.
- H2 and H3 are the confirmatory tests; nothing else is presented as confirmatory.
- **Power.** With n = 50 per arm, a one-sided Fisher test at α = 0.05 has about 0.8 power for 25% vs 5%. The smoke run suggests the ambiguous-text effect is large, so this is adequate. If FAR(B0) on the ambiguous text after 50 cycles is below 10%, report that as the finding, and do not add cycles to chase significance.

## 7. Budget and order

| Step | Runs | Estimate |
|---|---|---|
| C0 | 20 suites | 1 evening |
| E1 | 10 Sonnet suites (+ reuse C0) | < $2, minutes |
| E3 core | 300 cycles | 3 nights at ~4–6 min per cycle; ≈ $20–40 for the DMN track |
| R, L1 | 100 cycles | 1 night |
| E2 (S1) | 100 cycles | 1 night, ≈ $15 |
| E4 | 60 suites | 1 evening |

Order: C0 → E1 → E3 core → R/L1 → (EN) S1, E4.

## 8. Needed in code before E1/E4 (REVISION_PLAN step 15)

A **suite-only mode** of the runner:
- generates N test suites for a rule-set version, without any DMN track;
- logs each suite to JSONL (same `tracks.tests` fields, raw output, parsed suite);
- evaluates a given list of DMNs against every suite — the reference, its mutants with ids, or a reference of another version — and writes the kill matrix (`suite × mutant → outcome counts`).

C0, E1 and E4 all use this mode.

## 9. Log of deviations and decisions

| Date | Change | Reason |
|---|---|---|
| 2026-10-17 | Protocol v1 | — |
| 2026-10-17 | **C0 runs on `mortgage/disambiguated`** (`uk`, `tests/v2`) instead of `mortgage/ambiguous`. The selection metric in rule 2 is the median **consistent** mutation score without equivalents (`scoreConsistentWithoutEquivalent`: a mutant counts as killed only by cases that pass on the reference). The oracle's disagreement with the reference is reported separately (`referenceDisagreement`, `suitesRejectingReference`). The C0 runs on the ambiguous text (`experiments/c0-*-ambiguous.json`) are optional and descriptive only: how often each model reads rule priority in text order. | Smoke, before any E3 run: a gemma4:26b suite on the ambiguous text rejected the human reference on 3/10 cases. Two of them read rule priority in text order, which is the intended ambiguity, and one was an oracle error. A suite that rejects the reference kills every mutant by definition, so the protocol MS saturates at 1.0 and the C0 rule could not tell the candidates apart. On the disambiguated text a disagreement is an oracle error, not another valid reading. The ambiguous text itself stays unchanged, because it is the E3 condition. |
| 2026-10-17 | **C0 result and decision.** Mortgage/disambiguated, `uk`, `tests/v2`; median consistent MS without equivalents (51 mutants): Sonnet 5.5 0.98 (0/10 suites reject the reference, 0 failed calls, 23 s), gemma4:26b 0.96 (0/8, 2/10 failed calls = 20 %, 219 s), gpt-oss-20b 0.77 (4/9 suites reject the reference on the *disambiguated* text, 1/10 failed). **Test-track model for the cross-model arms: `local:gemma4:26b`** (rule 2; it passes rule 1 at exactly 20 %). gpt-oss-20b is excluded: an oracle that contradicts the explicit rule text in 4/9 suites is not a usable comparison point. | Protocol section 3. |
| 2026-10-17 | **C0 finding that changes the method before E3.** The surviving non-equivalent mutants are almost only two rule swaps that change priority where conditions overlap (`RULE_SWAP rule-3/rule-4`: Fixed30 before the LTV knockout; `rule-5/rule-6`: ARM before Fixed30). No gemma or gpt-oss suite kills `rule-3/rule-4`, and the union of all 8 gemma suites still scores 0.96: the gap is systematic for the model, not sampling noise. Sonnet: the union of 2 suites reaches 1.00 in 24/45 pairs. | Analysis of `runs/c0-*.jsonl` (Cowork). |
| 2026-10-17 | **New prompt `tests/v3`** = v2 + generic coverage requirements derived from hit-policy semantics, not from the mutants: (1) every pair of rules whose conditions can co-occur, (2) each threshold exactly at and just beyond, (3) near misses for multi-condition rules, (4) a no-rule case. `tests-adjudicate/v3` = v2. **Split:** mortgage is the development set; ФОП is held out. **Adoption rule (fixed now):** E3 uses `tests/v3` with gemma4:26b iff (a) on mortgage/disambiguated the median consistent MS of v3 ≥ v2, and (b) on the held-out ФОП references (`fop-eligibility/2026`, `fop-obligations/2026`) the median consistent MS of v3 ≥ v2 − 0.02 and the share of suites rejecting the reference is not higher than with v2. Otherwise E3 uses `tests/v2`. In both cases, v2 vs v3 is reported as an E1 result (arms E1-L2, E1-L3). | Prevents tuning the prompt on the evaluation data; makes the coverage requirement a measured part of the method. |
