# ФОП 2026 — candidates for equivalent mutants

Status (2026-10-18): **accepted** (`docs/EXPERIMENTS.md` section 9) and added as `equivalentMutants` to the `holdout-gemma-*-fop-*` configs. The ground-truth gaps below are closed: eligibility now has 43 cases and obligations 14. On Camunda the four former non-equivalent survivors are killed in every version, and only the four equivalent swaps survive (`FopRuleSetsTest`). The tables below describe the state before that extension.

## Method (protocol 4.1)
1. All `DmnMutator` mutants of `rulesets/<set>/2026/reference.dmn` (7 operators).
2. **Survivors:** mutants that pass the whole human ground truth under `StrictValidator` (38 cases for eligibility, 12 for obligations).
3. **Grid check of each survivor** against the reference with the Camunda engine, over the schema's input domain, densest around every threshold of the table:

| Rule set | Grid | Points |
|---|---|---|
| eligibility | `requestedGroup` {1, 2, 3, null} × `employeesCount` {0, 1, 9, 10, 11, null} × `activityType` (6 enum values + null) × `businessClients` {t, f, null} × `servicesToGeneralSystemClients` {t, f, null} × `annualIncome` {0, null, each limit −1 / = / +1} | 16 632 |
| obligations | `requestedGroup` {1, 2, 3, null} × `vatPayer` {t, f, null} × `monthlyIncome` {0, 1, 999.99, 12 345.67, 100 000, null} | 72 |

   No difference anywhere on the grid → **equivalent candidate**. A difference → not equivalent; the mutant marks a gap in the ground truth, with an example input.

## fop-eligibility/2026 — 137 mutants, 5 survive the ground truth

| Mutant | Grid differences | Verdict |
|---|---|---|
| `RULE_SWAP:rule-5/rule-6` | 0 / 16 632 | **Equivalent.** rule-5 needs group 1, rule-6 needs group 2: no input satisfies both, so their order cannot matter. |
| `RULE_SWAP:rule-9/rule-10` | 0 / 16 632 | **Equivalent.** Same argument: group 2 vs group 3. |
| `RULE_SWAP:rule-1/rule-2` | 1 980 | Not equivalent — ground-truth gap. Group 1 with hired employees *and* a forbidden activity: the text's check order gives `EMPLOYEES`, the mutant gives `ACTIVITY`. Example: group 1, 1 employee, `services`. |
| `RULE_SWAP:rule-3/rule-4` | 84 | Not equivalent — gap. Group 1 with business clients *and* income above the limit: the reference gives `CLIENTS`, the mutant `INCOME_LIMIT`. Example: group 1, 0 employees, `market_retail`, businessClients, income 1 444 050. |
| `CONDITION_DROP:rule-10/1` (`requestedGroup` 3 → any) | 378 | Not equivalent — gap. No group selected and income above the group-3 limit: the reference gives no result (the text says the result stays empty), the mutant gives `INCOME_LIMIT`. |

## fop-obligations/2026 — 40 mutants, 3 survive the ground truth

| Mutant | Grid differences | Verdict |
|---|---|---|
| `RULE_SWAP:rule-1/rule-2` | 0 / 72 | **Equivalent.** Group 1 vs group 2, disjoint. |
| `RULE_SWAP:rule-2/rule-3` | 0 / 72 | **Equivalent.** Group 2 vs group 3, disjoint. |
| `CONDITION_DROP:rule-3/1` (`requestedGroup` 3 → any) | 6 | Not equivalent — gap. No group selected and a VAT payer: the reference gives no result, the mutant computes the group-3 VAT rates. Example: group null, vatPayer true, monthly income 0 → mutant `singleTax 0, militaryLevy 0`. |

## Proposed `equivalentMutants`
- `fop-eligibility/2026`: `["RULE_SWAP:rule-5/rule-6", "RULE_SWAP:rule-9/rule-10"]` → 135 non-equivalent mutants.
- `fop-obligations/2026`: `["RULE_SWAP:rule-1/rule-2", "RULE_SWAP:rule-2/rule-3"]` → 38 non-equivalent mutants.

All four are swaps of adjacent rules whose `requestedGroup` conditions are disjoint, so the equivalence also holds analytically, not only on the grid.

## Side finding: gaps in the ФОП ground truth
The four non-equivalent survivors show input regions the human cases do not cover:
- several violations at once in group 1 (employees + activity; clients + income limit), where only the check order decides the reason;
- no group selected (`requestedGroup` null) combined with income above a limit, or with VAT registration.

With these mutants counted, the human ground truth kills 132/135 (eligibility) and 37/38 (obligations), not 100%. Whether to extend the ground truth is a separate decision: it would change the ФОП reference data that E4 already uses.
