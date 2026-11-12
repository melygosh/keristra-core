# Experiment protocol — independent dual-track generation of DMN tables

Status: v1, written 2026-10-17 in Cowork, **before** any experimental run. Changes after the first E3 run must be logged in section 9 with date and reason. Nothing in sections 2–6 may be changed after looking at E3 results.

## 0. Papers built on these experiments (decided 2026-10-18)

Each paper answers its own question on its own slice of the data. Later papers cite earlier ones. Numbers from an earlier paper appear only as context, never as new results.

| # | Paper | Question | Experiments |
|---|---|---|---|
| P1 | UA, фахове видання (Б) — improvement of the paired-generation method: conflict memory and detection of ambiguous business rules | When the conditions of rules overlap and their priority is implicit, convergence of the closed loop can be reached by oracle adaptation instead of a logic fix. Does conflict memory close this path, and does V3 turn the disagreement into an ambiguity signal for the analyst? | E3 core (B0/B1/V1 × ambiguous/disambiguated). E1 appears only as one paragraph of motivation (H8 vs H45). |
| P2 | EN, Scopus/WoS journal — how independent are the two tracks | Detection power of the test track and its limits: systematic blind spots at rule interactions, same vs different model on the tracks, interaction-aware oracle prompting (v2 vs v3), union of suites. | E1 (full), E2, C0 findings |
| P3 | EN, applied journal or conference — regulatory change | Do suites generated from version N+1 catch a stale table of version N, for parametric and structural changes? | E4 (ФОП 2024–2026) |

**Framing rules for P1** (consistent with the earlier papers):
- **Two metrics, kept apart.**
  - *Validity* — agreement with the system's own oracle. This is what the earlier papers measured, and those results stand.
  - *Correctness* — agreement with an independent human ground truth. It is new in P1.

  P1 adds the second dimension; it does not say the first was wrong.
- **Prior results fit the explicit-priority case.** The disambiguated text represents rules with explicit priority, the kind the earlier work used. The ambiguous text (implicit priority) is the new region.
- **Conflict memory and V3 are the next version of the Dispatcher.** They extend the earlier Petri-net model with conflict states (open/closed, AMBIGUOUS). V3 operationalises the dissertation's claim of detecting logical inconsistencies in textual rules.
- **Sources of evidence.** Motivation comes from the new runs only: arm B0 is Strategy B on the strict validator. The 2025-10 log is not analysed in P1.
- **Citing earlier papers.** They are cited for the method (Strategy B, Dispatcher, Petri nets) and for their convergence and cost results. They are not cited as evidence that accepted tables are error-free.
- **If B0 shows a high FAR on the disambiguated text too**, P1 reports it as a limitation of the validity metric that conflict memory addresses. The framing is decided after E3, from the data — not by changing sections 2–6.

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
| A1 | ESCALATE_ON_STABLE_DISAGREEMENT + escalation on ambiguity flags, test prompt `tests/v2b` | on |

That gives 8 arm-text combinations, 400 cycles (A1 added 2026-10-19, see section 9).

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
| H3a | Ambiguity-escalation rate (`AMBIGUOUS` in A1) is higher on the ambiguous text than on the disambiguated one | One-sided Fisher exact test, α = 0.05 (added 2026-10-19, before any full E3 run) |
| H5 | On the ambiguous text, FAR (accepted ∧ GT-wrong / valid cycles) is lower in A1 than in B1 | One-sided Fisher exact test, α = 0.05 (added 2026-10-19; corrected the same day before any full run, see section 9) |
| H4 | On the disambiguated text, FAR is low in every arm (control) | Wilson 95% CI reported; no test |
| X1 | Share of conflicts closed by `ORACLE_RETRACTION` vs `DMN_FIXED` | Exploratory, reported as is |
| X2 | Effect of RAG (R vs its base arm), language (L1 vs B1), model pairing (S1 vs B1) | Exploratory: Fisher two-sided, reported with CIs, no correction claims |

- All proportions are reported with Wilson 95% CIs.
- H2, H3, H3a and H5 are the confirmatory tests; nothing else is presented as confirmatory.
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
| 2026-10-18 | **ФОП equivalent mutants accepted** (`docs/fop-equivalent-candidates.md`). `fop-eligibility/*`: `RULE_SWAP:rule-5/rule-6`, `RULE_SWAP:rule-9/rule-10`. `fop-obligations/*`: `RULE_SWAP:rule-1/rule-2`, `RULE_SWAP:rule-2/rule-3`. These are adjacent rules with disjoint `requestedGroup` conditions, so they are equivalent analytically as well as on the Camunda grid. | Protocol 4.1 |
| 2026-10-18 | **ФОП ground truth extended, before any holdout or E4 run.** Eligibility goes from 38 to 43 cases (ids 39–43): group 1 with several violations at once, and no group selected combined with income above the group-3 limit or with forbidden clients. Obligations go from 12 to 14 cases (ids 13–14): no group selected for a VAT payer. The cases are added in `tools/rulesets/gen_fop.py`, so they exist in all three versions. The four non-equivalent survivors are now killed in every version (FEEL-subset check). | Without them the human ground truth (the E1 ceiling) had blind spots. The v2/v3 decision is measured on mutants of the reference table, not on the ground truth, so the extension does not touch the holdout. |
| 2026-10-18 | **Unparseable suites are infrastructure failures.** In suite-only mode, a suite with a JSON parse error is logged and not counted. Generation continues until `cycles` valid suites exist, capped at 2 × `cycles` attempts. The parse-error rate is reported per model. Responses are not repaired automatically, because that would change the oracle's content. | gemma4:26b: 2/10 parse errors in C0, 1/1 in the v3 smoke. Rule as for `ERROR` cycles (section 4.2). |
| 2026-10-18 | **Paper split P1/P2/P3 and the P1 framing rules** (section 0). | Avoid scope creep and overlapping publications |
| 2026-10-18 | **Test prompt for E3: `tests/v2`** (adoption rule of 2026-10-17 applied as fixed). gemma4:26b, `uk`, 10 valid suites per run, median consistent MS without equivalents, share of suites rejecting the reference: (a) mortgage/disambiguated: v3 0.97 ≥ v2 0.96 ✓ (both 0/10 rejecting); (b) ФОП holdout, MS: eligibility v3 0.885 ≥ v2 0.90 − 0.02 ✓, obligations v3 0.97 ≥ v2 0.97 − 0.02 ✓; (b) ФОП holdout, rejecting share: eligibility v3 8/10 > v2 5/10 ✗, obligations v3 4/10 > v2 0/10 ✗. Condition (b) fails, so E3 uses `tests/v2`. Parse errors (not counted): mortgage v3 2/12, eligibility v2 1/11, v3 0/10, obligations 0/10 both. Also measured (E1-S3): Sonnet 5.5 + v3 on mortgage, median 1.00 (7/10 suites kill all 51 non-equivalent mutants, `RULE_SWAP rule-3/rule-4` included), 0/10 rejecting. | Rule fixed before the runs. Finding for P2: the coverage requirement raises detection power for a strong oracle (Sonnet) but, for gemma, mostly adds oracle errors on the harder ФОП rules; gemma still never kills `RULE_SWAP rule-3/rule-4` on mortgage. Logs: `runs/e1-*-v3-mortgage.jsonl`, `runs/holdout-gemma-*.jsonl` (commit `a546e6b`). |
| 2026-10-18 | **E1 v3 and ФОП holdout results; v3 adoption rule applied: E3 uses `tests/v2`.** Median consistent MS without equivalents:<br>• mortgage: gemma v2 0.961 → v3 0.971 (rule (a) met); Sonnet v2 0.980 → v3 1.000.<br>• ФОП eligibility 2026: gemma v2 0.900 → v3 0.889 (within −0.02); suites rejecting the reference v2 5/10 → v3 8/10 (**rule (b) fails**).<br>• ФОП obligations 2026: 0.974 → 0.974; suites rejecting the reference 0/10 → 4/10 (**fails**).<br>So E3 runs with `local:gemma4:26b` + `tests/v2`, and v2 vs v3 is reported in P2. | Rule fixed on 2026-10-17, applied as written. |
| 2026-10-18 | **What the holdout disagreements are (for P2/P3, not for E3).** Eligibility, v2 and v3 alike: gemma computes the derived income limits wrongly. It labels 7 211 898 / 10 092 049 as "exactly at the limit" (true values 7 211 598 / 10 091 049), and labels 10 089 050 as above the limit. The oracle is wrong on arithmetic of the derived thresholds, not on the rules. Obligations v3: the near-miss requirement pushed gemma into cases with `vatPayer` or `monthlyIncome` null. There it expects "no rule", while the reference returns 5% or null outputs. Gemma also makes plain arithmetic errors (1% instead of 5%) and does not round to kopecks. **Open issue in our own data:** the ФОП texts do not define behaviour for missing values (the mortgage disambiguated text does), and `fop-obligations` returns a row with null outputs when `monthlyIncome` is null. This must be resolved in the text, the reference and the ground truth before E4. | Analysis of `runs/holdout-*.jsonl` (Cowork) |
| 2026-10-19 | **How `ERROR` cycles are replaced (implements section 4.2).** An `ERROR` cycle stays in the log under its number and is not run again. The run continues with the next cycle number (new seeds, offset `cycle × 1000 + attempt`) until `cycles` valid cycles exist. One session allows at most `cycles` `ERROR` cycles, so at most 2 × `cycles` cycles run, the same cap as for unparseable suites. A resumed run skips every logged cycle and continues the numbering, with a fresh `ERROR` allowance. `run_finished` reports `target`, `validCycles`, `errorCycles` and `budgetExhausted`. Outcome metrics (FAR, ground truth, attempts) use valid cycles only. Tokens and cost include `ERROR` cycles. | Re-running the same cycle number would reuse the same test-track seed, so a deterministic local-model failure would repeat. This is the same rule as suite mode (`gen-N`). Commit `100effe`, smoke `runs/smoke/e3/e3-b0-ambiguous-smoke2.jsonl`. |
| 2026-10-19 | **Pilot-based amendment before any full E3 run: arm A1 (ambiguity flags) and a literal ground truth.** E3 smokes (1–2 cycles per arm, `runs/smoke/e3/`): on the ambiguous text every arm accepted a table that fails the domain ground truth on the 8 conflict cases (37/45), including B1 and V1 at the first attempt; on the disambiguated text every arm accepted a 45/45 table. Cause: gemma4:26b *does* generate conflict cases, but in 8 of 10 it expects the same output as Sonnet's table — both read rule priority from the sentence order under the stated FIRST hit policy. The tracks agree, so no conflict arises and conflict memory has nothing to keep. The ambiguous text has two defensible readings: the literal one (sentence order = priority under FIRST) and the domain one used by our ground truth (knockouts override, "without automatic product assignment").<br>**Changes:** (1) prompt `tests/v2a` = v2 + an instruction to mark test cases whose expected output depends on an interpretation the rules do not state explicitly (`"ambiguous": true`, `"ambiguityNote"`); sentence order is defined as *not* an explicit precedence statement; `tests-adjudicate/v2a` likewise. (2) Arm A1: V1 + conflict memory + a cycle ends `AMBIGUOUS` when its accepted attempt contains flagged cases (flagged cases are still validated normally). A1 runs on both texts, n = 50. (3) Confirmatory H3a and H5 added; H2 and H3 unchanged. (4) `mortgage/ambiguous/ground-truth.literal.json`: the literal reading (Fixed30 > ARM7/1 > knockouts); it differs from the domain ground truth exactly in the 8 conflict cases (ids 9–16). FAR against the literal ground truth is reported as exploratory, next to the domain FAR. (5) L1 (`en`) moves from P1 to P2 scope; it is run after the P1 arms. | B0/B1/V1 and their hypotheses stay as fixed; the smoke cycles are not counted in any arm. The amendment is made on 1–2 pilot cycles per arm, before the full runs, and is reported as such in P1. |
| 2026-10-19 | **Second pilot amendment (A1 smoke, 2 cycles per text), before any full E3 run: A1 uses `tests/v2b`; H5 corrected.** Results:<br>• With `tests/v2a`, gemma4:26b generated the conflict cases on the ambiguous text but never flagged them (0 flags, both cycles accepted, both GT-wrong 37/45, both literal-correct). On the disambiguated text it produced 0 flags, and both cycles were correct.<br>• With `tests/v2b` (v2a + a PRECEDENCE paragraph — a hit-policy line and the written order of rules are not a precedence statement; precedence is explicit only when stated in words — and a self-check over cases where several rules apply): on the ambiguous text, 1 of 2 cycles ended `AMBIGUOUS` (3 flagged cases, all three conflict types, with correct notes); the other cycle was accepted with the conflict cases unflagged. On the disambiguated text: 0 flags in 2 cycles, both correct, and gemma expected `ManualReview` for knockout-vs-fixed cases, i.e. it follows explicit priority.<br>**Decision:** A1 runs with `tests/v2b`. `v2a` stays in the repository as a tried variant and is reported as such.<br>**H5 corrected:** "share of GT-wrong among accepted" cannot drop when every table on the ambiguous text implements the literal reading, because escalation removes wrong tables from the accepted set but the remaining accepted ones are still wrong. The intended quantity is FAR (accepted ∧ GT-wrong / valid cycles). | Pilot evidence is very small (2 cycles per text); the expected effect (escalations on ambiguous vs none on disambiguated) is reported with CIs. The v2b wording is a definition of explicit precedence, not a reference to the mortgage rules. No full-run data has been seen. |
| 2026-10-22 | **E3 full results (n = 50 valid cycles per arm) and P1 reframing.** Ambiguous text, accepted ∧ GT-wrong: B0 49/50, B1 49/50, V1 49/50 (+1 `AMBIGUOUS`), A1 36/50 (+14 `AMBIGUOUS`). Disambiguated text: 0/50 in every arm (0/200, Wilson 95% CI [0; 1.9%]); A1 2/50 `AMBIGUOUS`.<br>• **H2 not supported** (49/50 vs 49/50): the tracks share the literal reading (sentence order under FIRST), so no conflict arises; the 12 conflicts that did arise in B1 were all closed by `ORACLE_RETRACTION` (X1).<br>• **H3 not supported** (V1 1/50 vs 0/50, p = 0.5).<br>• **H3a supported** (A1 14/50 vs 2/50, one-sided Fisher p = 0.0009).<br>• **H5 supported** (FAR A1 36/50 vs B1 49/50, p = 0.0002).<br>• **H4**: 0/50 per arm.<br>• Every accepted table on the ambiguous text matches the literal ground truth 45/45 and the domain ground truth 37/45 (the 8 conflict cases).<br>• The 2 A1 escalations on the disambiguated text point at a residual ambiguity the authors missed (score 639.5 vs "нижчий за 640 (639 і менше)"); reported both as 2/50 = 4% [1.1; 13.5%] and with this inspection result.<br>• Cost per valid cycle (tracks only, wall time dominated by the local test model; adjudication latency is not logged): ambiguous B0 248 s / 18.3k tok, B1 320 s / 18.5k, V1 237 s / 17.1k, A1 337 s / 19.2k; disambiguated B0 245 / 16.9k, B1 255 / 17.9k, V1 254 / 17.5k, A1 341 / 20.5k. Against its direct base V1, A1 costs about +35–40% time and +12–17% tokens (longer v2b suites with notes), not +5% as first estimated against B1.<br>• `ERROR` cycles: A1-disambiguated cycles 1–12 = `ConnectException` (Ollama not reachable at session start), B0-ambiguous 36–38 = `UnresolvedModelServerException`; infrastructure, replaced per section 4.2.<br>• Test-suite parse-failure rate is higher in A1-disambiguated (~0.2) than elsewhere (0.07–0.14); exploratory, reported as is.<br>**P1 reframing (section 0 updated in substance):** the core claim moves from conflict memory to ambiguity escalation. Disagreement-based mechanisms (conflict memory, V1) cannot see a reading both tracks share; only an in-track ambiguity signal (A1) reduces FAR. H2 is reported as a pre-registered negative result. Formal frame: the dissertation's safety property (2.27) guarantees validity V(D,Ξ), i.e. equivalence to the default reading ρ₀; correctness needs equivalence to the intended reading ρ*; the gap equals the ambiguity of the rule text and is measured by the two ground truths. | Decided after the full runs, from the data, as section 0 allows ("framing is decided after E3"); hypotheses and their tests are unchanged. |
| 2026-11-12 | **E3b: post hoc follow-up, exploratory (one run).** Rule-set version `mortgage/ambiguous-nohp` = `mortgage/ambiguous` with the first line `Hit Policy: FIRST` removed and one sentence added to the first paragraph (uk: «Для кожної заявки визначається рівно один результат»; en: "Exactly one result is determined for each application"). Rule order and wording are otherwise unchanged; both ground truths and the reference are byte copies. Run `e3b-b0-ambiguous-nohp`: arm B0 as in section 5 (same models, prompts, validator, budget, seed base), n = 50 valid cycles. **Reported:** FAR against the domain ground truth with a Wilson 95% CI, the share of accepted tables matching the literal ground truth 45/45, the hit policies chosen by the DMN track, and a two-sided Fisher test against `e3-b0-ambiguous` (49/50). Nothing here is confirmatory, and sections 2–6 are unchanged. **Smoke (2 cycles, `runs/smoke/e3b/`, dirty tree, not counted):** both cycles accepted at the first attempt; the DMN track chose `FIRST` and sentence order in both; both tables are 45/45 literal and 37/45 domain (the 8 conflict cases); both suites contain a knockout-vs-product case expecting the product. No `RULE ORDER` table and no validator failure, so the added sentence is kept as written. | Review of the P1 manuscript after the full runs: the ambiguous text opens with a hit-policy line, and `dmn/v1` tells the generator to take the hit policy from the rules, so the literal reading could be an artefact of that line rather than of the rule text. The sentence about one result is needed because without any hit policy `dmn/v1` falls back to `RULE ORDER`, which returns every match. The run is decided after seeing all E3 results and is reported as such. |
| 2026-11-12 | **E3-S: replication series with Sonnet 5.5 on the test track (post hoc, decided after all E3 results).** Nine runs, n = 50 valid cycles each: `e3s-{b0,b1,v1,a1}-{ambiguous,disambiguated}` are the eight E3 arm-text combinations with one change, `tests.model = anthropic:claude-sonnet-5-5` (default sampling, no temperature or seed, F16; `maxTokens` 16000; prompts `tests/v2`, and `tests/v2b` in A1, unchanged; re-adjudication also runs on Sonnet). `e3s-b0-ambiguous-nohp` is B0 on `mortgage/ambiguous-nohp`. Everything else is as in section 2. **Supersedes the planned run `e3b-b0-ambiguous-nohp`** (local test model, entry above): it is not run; its 2-cycle smoke stays in `runs/smoke/e3b/`. **Analysis, fixed now, before any E3-S cycle:** the same quantities and tests as H2, H3, H3a, H5 (one-sided Fisher, α = 0.05) and H4 (Wilson CI), computed within E3-S and reported as a replication, labelled S-H2 … S-H4; the original E3 results and their confirmatory status are unchanged and both series are reported. For `nohp`: FAR against the domain ground truth with a Wilson 95% CI, the share of accepted tables matching the literal ground truth 45/45, the hit policies chosen by the DMN track, and a two-sided Fisher test against `e3s-b0-ambiguous`. Exploratory, reported as is: conflicts closed by `DMN_FIXED` vs `ORACLE_RETRACTION`, flags per conflict type, cost. **Stated limits:** both tracks now use the same model, so agreement between them is weaker evidence of a shared reading than in E3 and is not presented as cross-model agreement; `tests/v2b` was tuned with gemma4:26b on this domain. Runner: `scripts/run-e3s.sh`. | (1) The local test model needs 2–4 min per suite and the machine for several nights; Sonnet takes about 25 s per suite (C0). (2) External validity: the 28% ambiguity recall of A1 and the null results of B1/V1 may be properties of gemma4:26b; C0 showed Sonnet is the stronger oracle (median MS 0.98 vs 0.96, kills the priority rule swap). (3) The `nohp` comparison needs a baseline with the same test model. Expected cost about $45–55. |
