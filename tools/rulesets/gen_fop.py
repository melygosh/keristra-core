import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from fop_params import P, fmt, fmt_en
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'rulesets')

# ---------- texts ----------
def elig_uk(v, p):
    return f"""Hit Policy: FIRST
Визначте, чи може фізична особа-підприємець (ФОП) застосовувати обрану групу спрощеної системи оподаткування (єдиного податку) у {v} році згідно з пунктом 291.4 Податкового кодексу України.
Граничний річний дохід визначається як кратне мінімальної заробітної плати, встановленої законом на 1 січня звітного року. На 1 січня {v} року мінімальна заробітна плата становить {fmt(p['mzp'])} грн. Граничний річний дохід становить: для 1 групи — 167 мінімальних заробітних плат, для 2 групи — 834, для 3 групи — 1167 мінімальних заробітних плат.{p['mzp_note_uk']}
1 група: ФОП не може мати найманих працівників. Дозволено лише роздрібний продаж товарів з торговельних місць на ринках (market_retail) або надання побутових послуг населенню (household_services). ФОП 1 групи не може продавати товари чи надавати послуги юридичним особам або іншим ФОП.
2 група: ФОП може мати одночасно не більше 10 найманих працівників. ФОП 2 групи не може надавати послуги (services, household_services) юридичним особам і ФОП, які не є платниками єдиного податку. Продаж товарів (goods_trade), виробництво (production), ресторанне господарство (restaurant) і роздрібний продаж на ринках дозволені для будь-яких покупців.
3 група: обмежень щодо кількості найманих працівників, видів діяльності з наведеного переліку чи покупців немає.
Для будь-якої групи: якщо річний дохід перевищує граничний для обраної групи, ФОП не може її застосовувати. Дохід, що дорівнює граничному, перевищенням не є.
Перевірки виконуються в такому порядку, і результат визначає перше виявлене порушення: кількість найманих працівників, вид діяльності, покупці, річний дохід.
Результат зберігається у змінних eligible (true — групу можна застосовувати, false — не можна) та reason з одним зі значень: "OK", "EMPLOYEES", "ACTIVITY", "CLIENTS", "INCOME_LIMIT".
Якщо обрану групу не вказано, результат залишається порожнім."""

def elig_en(v, p):
    return f"""Hit Policy: FIRST
Determine whether a Ukrainian sole proprietor (FOP) may apply the selected group of the simplified taxation system (single tax) in {v}, according to article 291.4 of the Tax Code of Ukraine.
The annual income limit is a multiple of the minimum wage set by law on 1 January of the reporting year. On 1 January {v} the minimum wage is UAH {fmt_en(p['mzp'])}. The annual income limit is 167 minimum wages for group 1, 834 for group 2 and 1167 minimum wages for group 3.{p['mzp_note_en']}
Group 1: the FOP may not have hired employees. Only retail sale of goods from market stalls (market_retail) or household services to the population (household_services) are allowed. A group 1 FOP may not sell goods or provide services to legal entities or other FOPs.
Group 2: the FOP may have at most 10 hired employees at the same time. A group 2 FOP may not provide services (services, household_services) to legal entities and FOPs that are not single-tax payers. Sale of goods (goods_trade), production (production), the restaurant business (restaurant) and retail sale from market stalls are allowed for any buyer.
Group 3: there are no restrictions on the number of hired employees, on the listed activity types or on buyers.
For every group: if the annual income exceeds the limit of the selected group, the FOP may not apply it. Income equal to the limit is not an excess.
The checks are applied in this order, and the first violation found determines the result: number of hired employees, activity type, buyers, annual income.
The result is stored in the variables eligible (true if the group may be applied, false otherwise) and reason with one of the values: "OK", "EMPLOYEES", "ACTIVITY", "CLIENTS", "INCOME_LIMIT".
If no group is selected, the result stays empty."""

def oblig_uk(v, p):
    levy = (f"""Військовий збір: для 1 і 2 груп — 10% мінімальної заробітної плати, встановленої на 1 січня року; для 3 групи — 1% місячного доходу незалежно від реєстрації платником ПДВ.
""" if p['levy'] else f"""Військовий збір платники єдиного податку у {v} році не сплачують.
""")
    out = ('Результат зберігається у змінних singleTax та militaryLevy (грн на місяць).' if p['levy']
           else 'Результат зберігається у змінній singleTax (грн на місяць).')
    return f"""Hit Policy: FIRST
Визначте щомісячні платежі ФОП — платника єдиного податку 1–3 груп у {v} році.
Єдиний податок (застосовуйте максимальні ставки): 1 група — 10% прожиткового мінімуму для працездатних осіб, встановленого на 1 січня року (у {v} році — {fmt(p['pm'])} грн); 2 група — 20% мінімальної заробітної плати, встановленої на 1 січня року (у {v} році — {fmt(p['mzp'])} грн); 3 група — 5% місячного доходу, а для платника ПДВ — 3% місячного доходу.{p['mzp_note_uk']}
{levy}Для 1 і 2 груп платежі не залежать від розміру доходу. Реєстрація платником ПДВ впливає лише на ставку єдиного податку 3 групи.
Суми округлюються до копійок (2 знаки після коми).
{out} Якщо групу не вказано, результат залишається порожнім."""

def oblig_en(v, p):
    levy = ("""Military levy: for groups 1 and 2, 10% of the minimum wage set on 1 January of the year; for group 3, 1% of the monthly income regardless of VAT registration.
""" if p['levy'] else f"""Single-tax payers do not pay the military levy in {v}.
""")
    out = ('The result is stored in the variables singleTax and militaryLevy (UAH per month).' if p['levy']
           else 'The result is stored in the variable singleTax (UAH per month).')
    return f"""Hit Policy: FIRST
Determine the monthly payments of a FOP who pays the single tax in groups 1–3 in {v}.
Single tax (apply the maximum rates): group 1 — 10% of the subsistence minimum for able-bodied persons set on 1 January of the year (UAH {fmt_en(p['pm'])} in {v}); group 2 — 20% of the minimum wage set on 1 January of the year (UAH {fmt_en(p['mzp'])} in {v}); group 3 — 5% of the monthly income, or 3% of the monthly income for a VAT payer.{p['mzp_note_en']}
{levy}For groups 1 and 2 the payments do not depend on the income. VAT registration affects only the group 3 single-tax rate.
Amounts are rounded to kopecks (2 decimal places).
{out} If no group is selected, the result stays empty."""

# ---------- ground truth: one input set shared by all versions ----------
BASE = dict(requestedGroup=1, annualIncome=500000, monthlyIncome=40000, employeesCount=0, activityType='market_retail',
            businessClients=False, servicesToGeneralSystemClients=False, vatPayer=False)
def B(**kw): return dict(BASE, **kw)

def elig_expected(a, p):
    g = a.get('requestedGroup')
    if g is None: return None
    emp, act, inc = a['employeesCount'], a['activityType'], a['annualIncome']
    if g == 1:
        if emp > 0: return (False, 'EMPLOYEES')
        if act not in ('market_retail', 'household_services'): return (False, 'ACTIVITY')
        if a['businessClients']: return (False, 'CLIENTS')
    if g == 2:
        if emp > 10: return (False, 'EMPLOYEES')
        if act in ('services', 'household_services') and a['servicesToGeneralSystemClients']: return (False, 'CLIENTS')
    if inc > p['L'][g]: return (False, 'INCOME_LIMIT')
    return (True, 'OK')

E = []  # (category, description, input)
for g, act in ((1, 'market_retail'), (2, 'services'), (3, 'services')):
    for v in ('2024', '2025', '2026'):
        L = P[v]['L'][g]
        E.append((f'boundary:limit-{v}', f'{g} група, дохід рівно на ліміті {v} року ({fmt(L)} грн)', B(requestedGroup=g, activityType=act, annualIncome=L)))
        E.append((f'boundary:limit-{v}', f'{g} група, дохід на 1 грн більший за ліміт {v} року', B(requestedGroup=g, activityType=act, annualIncome=L + 1)))
E += [
 ('g1:employees', '1 група, один найманий працівник', B(employeesCount=1)),
 ('g1:activity', '1 група, надання послуг (не побутових)', B(activityType='services')),
 ('g1:activity', '1 група, продаж товарів поза ринком', B(activityType='goods_trade')),
 ('g1:ok', '1 група, побутові послуги населенню', B(activityType='household_services')),
 ('g1:clients', '1 група, продаж на ринку, але покупці — юрособи', B(businessClients=True)),
 ('g1:priority', '1 група, 2 працівники і перевищення доходу — першим іде порушення щодо працівників', B(employeesCount=2, annualIncome=2_000_000)),
 ('g1:priority', '1 група, послуги і покупці-юрособи — першим іде вид діяльності', B(activityType='services', businessClients=True)),
 ('g1:clients', '1 група, побутові послуги ФОП-платнику єдиного податку — теж заборонено', B(activityType='household_services', businessClients=True)),
 ('g2:employees', '2 група, рівно 10 найманих працівників', B(requestedGroup=2, activityType='services', employeesCount=10)),
 ('g2:employees', '2 група, 11 найманих працівників', B(requestedGroup=2, activityType='services', employeesCount=11)),
 ('g2:clients', '2 група, послуги юрособам на загальній системі', B(requestedGroup=2, activityType='services', businessClients=True, servicesToGeneralSystemClients=True)),
 ('g2:clients', '2 група, побутові послуги юрособам на загальній системі', B(requestedGroup=2, activityType='household_services', businessClients=True, servicesToGeneralSystemClients=True)),
 ('g2:ok', '2 група, продаж товарів юрособам на загальній системі — дозволено', B(requestedGroup=2, activityType='goods_trade', businessClients=True, servicesToGeneralSystemClients=True)),
 ('g2:ok', '2 група, ресторан, серед клієнтів юрособи на загальній системі — дозволено', B(requestedGroup=2, activityType='restaurant', businessClients=True, servicesToGeneralSystemClients=True)),
 ('g2:ok', '2 група, послуги платникам єдиного податку', B(requestedGroup=2, activityType='services', businessClients=True)),
 ('g2:priority', '2 група, 11 працівників і заборонені клієнти — першим іде порушення щодо працівників', B(requestedGroup=2, activityType='services', employeesCount=11, businessClients=True, servicesToGeneralSystemClients=True)),
 ('g2:priority', '2 група, заборонені клієнти і перевищення доходу — першими йдуть покупці', B(requestedGroup=2, activityType='services', businessClients=True, servicesToGeneralSystemClients=True, annualIncome=9_000_000)),
 ('g3:ok', '3 група, 50 працівників, послуги юрособам на загальній системі', B(requestedGroup=3, activityType='services', employeesCount=50, businessClients=True, servicesToGeneralSystemClients=True)),
 ('g3:ok', '3 група, платник ПДВ, виробництво', B(requestedGroup=3, activityType='production', vatPayer=True, employeesCount=5)),
 ('null', 'Групу не вказано', B(requestedGroup=None)),
 # added 2026-10-18: ground-truth gaps found by the equivalent-mutant check (docs/fop-equivalent-candidates.md)
 ('g1:priority', '1 група, 1 працівник і послуги — першим іде порушення щодо працівників', B(employeesCount=1, activityType='services')),
 ('g1:priority', '1 група, покупці-юрособи і перевищення доходу — першими йдуть покупці', B(businessClients=True, annualIncome=2_000_000)),
 ('g1:priority', '1 група, продаж товарів і перевищення доходу — першим іде вид діяльності', B(activityType='goods_trade', annualIncome=2_000_000)),
 ('null', 'Групу не вказано, дохід вище ліміту 3 групи', B(requestedGroup=None, annualIncome=20_000_000)),
 ('null', 'Групу не вказано, 15 працівників, послуги юрособам на загальній системі', B(requestedGroup=None, employeesCount=15, activityType='services', businessClients=True, servicesToGeneralSystemClients=True)),
]

def oblig_expected(a, p):
    g = a.get('requestedGroup')
    if g is None: return None
    if g == 1: r = dict(singleTax=p['tax1'], militaryLevy=p['levy12'])
    elif g == 2: r = dict(singleTax=p['tax2'], militaryLevy=p['levy12'])
    else:
        rate = 0.03 if a['vatPayer'] else 0.05
        r = dict(singleTax=round(a['monthlyIncome'] * rate + 1e-9, 2), militaryLevy=round(a['monthlyIncome'] * 0.01 + 1e-9, 2))
    if not p['levy']: r.pop('militaryLevy')
    return r

O = [
 ('g1', '1 група, місячний дохід 20 000 грн', B(monthlyIncome=20000)),
 ('g1', '1 група, нульовий дохід — податок фіксований', B(monthlyIncome=0)),
 ('g1', '1 група, дохід не вказано — податок фіксований', B(monthlyIncome=None)),
 ('g2', '2 група, місячний дохід 150 000 грн', B(requestedGroup=2, activityType='services', monthlyIncome=150000)),
 ('g2:vat-trap', '2 група з позначкою платника ПДВ — ПДВ на ставку 2 групи не впливає', B(requestedGroup=2, activityType='services', monthlyIncome=150000, vatPayer=True)),
 ('g3', '3 група без ПДВ, дохід 100 000 грн', B(requestedGroup=3, activityType='services', monthlyIncome=100000)),
 ('g3:vat', '3 група, платник ПДВ, дохід 100 000 грн', B(requestedGroup=3, activityType='services', monthlyIncome=100000, vatPayer=True)),
 ('g3:rounding', '3 група без ПДВ, дохід 12 345,67 грн — округлення до копійок', B(requestedGroup=3, activityType='services', monthlyIncome=12345.67)),
 ('g3:rounding', '3 група, платник ПДВ, дохід 12 345,67 грн — округлення до копійок', B(requestedGroup=3, activityType='services', monthlyIncome=12345.67, vatPayer=True)),
 ('g3', '3 група без ПДВ, нульовий дохід', B(requestedGroup=3, activityType='services', monthlyIncome=0)),
 ('g3', '3 група без ПДВ, дохід 841 000 грн (великий місяць)', B(requestedGroup=3, activityType='production', monthlyIncome=841000)),
 ('null', 'Групу не вказано', B(requestedGroup=None)),
 # added 2026-10-18: ground-truth gaps found by the equivalent-mutant check
 ('null', 'Групу не вказано, платник ПДВ, нульовий дохід', B(requestedGroup=None, vatPayer=True, monthlyIncome=0)),
 ('null', 'Групу не вказано, платник ПДВ, дохід 100 000 грн', B(requestedGroup=None, vatPayer=True, monthlyIncome=100000)),
]

def build(cases, expfn, outputs, p):
    res = []
    for i, (cat, desc, a) in enumerate(cases, 1):
        app = dict(id=i, **a)
        e = expfn(app, p)
        if e is None: exp = None
        elif isinstance(e, tuple): exp = [dict(zip(outputs, e))]
        else: exp = [e]
        res.append(dict(id=i, category=cat, description=desc, object={'fop': app}, expected=exp))
    return res

# ---------- reference DMN ----------
NS = 'https://www.omg.org/spec/DMN/20191111/MODEL/'
def dmn(name, inputs, outputs, rules):
    o = ['<?xml version="1.0" encoding="UTF-8"?>', f'<definitions xmlns="{NS}" id="definitions" name="definitions" namespace="http://camunda.org/schema/1.0/dmn">',
         f'  <decision id="decision" name="{name}">', '    <decisionTable id="decisionTable" hitPolicy="FIRST">']
    for i, (n, t) in enumerate(inputs, 1):
        o += [f'      <input id="input-{i}" label="fop.{n}">', f'        <inputExpression id="input-{i}-exp" typeRef="{t}" expressionLanguage="feel">',
              f'          <text>fop.{n}</text>', '        </inputExpression>', '      </input>']
    for i, (n, t) in enumerate(outputs, 1):
        o.append(f'      <output id="output-{i}" label="{n}" name="{n}" typeRef="{t}" />')
    for r, (conds, outs) in enumerate(rules, 1):
        o.append(f'      <rule id="rule-{r}">')
        for i, (n, _) in enumerate(inputs, 1):
            o += [f'        <inputEntry id="rule-{r}-input-{i}">', f'          <text>{conds.get(n, "")}</text>', '        </inputEntry>']
        for i, val in enumerate(outs, 1):
            o += [f'        <outputEntry id="rule-{r}-output-{i}">', f'          <text>{val}</text>', '        </outputEntry>']
        o.append('      </rule>')
    o += ['    </decisionTable>', '  </decision>', '</definitions>', '']
    return '\n'.join(o)

def elig_dmn(v, p):
    I = [('requestedGroup', 'integer'), ('employeesCount', 'integer'), ('activityType', 'string'), ('businessClients', 'boolean'),
         ('servicesToGeneralSystemClients', 'boolean'), ('annualIncome', 'number')]
    L = p['L']; F, T = 'false', 'true'
    R = [({'requestedGroup': '1', 'employeesCount': '&gt; 0'}, [F, '"EMPLOYEES"']),
         ({'requestedGroup': '1', 'activityType': 'not("market_retail","household_services")'}, [F, '"ACTIVITY"']),
         ({'requestedGroup': '1', 'businessClients': 'true'}, [F, '"CLIENTS"']),
         ({'requestedGroup': '1', 'annualIncome': f'&gt; {L[1]}'}, [F, '"INCOME_LIMIT"']),
         ({'requestedGroup': '1'}, [T, '"OK"']),
         ({'requestedGroup': '2', 'employeesCount': '&gt; 10'}, [F, '"EMPLOYEES"']),
         ({'requestedGroup': '2', 'activityType': '"services","household_services"', 'servicesToGeneralSystemClients': 'true'}, [F, '"CLIENTS"']),
         ({'requestedGroup': '2', 'annualIncome': f'&gt; {L[2]}'}, [F, '"INCOME_LIMIT"']),
         ({'requestedGroup': '2'}, [T, '"OK"']),
         ({'requestedGroup': '3', 'annualIncome': f'&gt; {L[3]}'}, [F, '"INCOME_LIMIT"']),
         ({'requestedGroup': '3'}, [T, '"OK"'])]
    return dmn(f'FOP single-tax group eligibility {v} (human reference)', I, [('eligible', 'boolean'), ('reason', 'string')], R)

def oblig_dmn(v, p):
    I = [('requestedGroup', 'integer'), ('vatPayer', 'boolean')]
    lv = p['levy']
    outs = [('singleTax', 'number')] + ([('militaryLevy', 'number')] if lv else [])
    def row(tax, levy): return [tax] + ([levy] if lv else [])
    R = [({'requestedGroup': '1'}, row(str(p['tax1']), str(p['levy12']))),
         ({'requestedGroup': '2'}, row(str(p['tax2']), str(p['levy12']))),
         ({'requestedGroup': '3', 'vatPayer': 'true'}, row('decimal(fop.monthlyIncome * 0.03, 2)', 'decimal(fop.monthlyIncome * 0.01, 2)')),
         ({'requestedGroup': '3'}, row('decimal(fop.monthlyIncome * 0.05, 2)', 'decimal(fop.monthlyIncome * 0.01, 2)'))]
    return dmn(f'FOP monthly single tax and military levy {v} (human reference)', I, outs, R)

SOURCES = ("Tax Code of Ukraine (ПКУ), п. 291.4 (groups, limits as 167/834/1167 minimum wages on 1 January), п. 293.2-293.3 (single-tax rates). "
           "Values cross-checked 2026-10-17 against: biz.ligazakon.net/news/224492 (2024: MZP 7 100, PM 3 028, limits 1 185 700 / 5 921 400 / 8 285 700, single tax 302.80 / 1 420); "
           "news.dtkt.ua/.../107438 and buhplatforma.com.ua/news/113456 (2025: MZP 8 000, PM 3 028; 2026: MZP 8 647, PM 3 328, limits 1 444 049 / 7 211 598 / 10 091 049, military levy 10% MZP for groups 1-2, 1% income for group 3); "
           "biz.ligazakon.net/news/232910 (military levy for single-tax payers from 1 January 2025).")
UNVERIFIED = ["Exact number and article of the law that introduced the military levy for single-tax FOPs (sources disagree: bill 9319 vs law 4015-IX) — only the start date 2025-01-01 is cross-checked.",
              "Group 1/2 single-tax amounts are the statutory maxima; real rates are set by local councils (modelled as maxima on purpose, stated in the rule text).",
              "Client restrictions are simplified to two flags (businessClients, servicesToGeneralSystemClients); non-residents, budget institutions and excise goods are out of scope."]

for decision, textfn_uk, textfn_en, cases, expfn, outs, dmnfn in (
        ('fop-eligibility', elig_uk, elig_en, E, elig_expected, ['eligible', 'reason'], elig_dmn),
        ('fop-obligations', oblig_uk, oblig_en, O, oblig_expected, None, oblig_dmn)):
    for v, p in P.items():
        d = os.path.join(ROOT, decision, v); os.makedirs(d, exist_ok=True)
        open(os.path.join(d, 'rules.uk.txt'), 'w').write(textfn_uk(v, p) + '\n')
        open(os.path.join(d, 'rules.en.txt'), 'w').write(textfn_en(v, p) + '\n')
        gt = build(cases, expfn, outs, p)
        open(os.path.join(d, 'ground-truth.json'), 'w').write(json.dumps(gt, ensure_ascii=False, indent=2) + '\n')
        open(os.path.join(d, 'reference.dmn'), 'w').write(dmnfn(v, p))
        outputs = ['eligible', 'reason'] if decision == 'fop-eligibility' else (['singleTax', 'militaryLevy'] if p['levy'] else ['singleTax'])
        meta = dict(domain='fop', hitPolicy='FIRST', outputs=outputs, ambiguous=False, languages=['uk', 'en'], version=v,
                    parameters=dict(minimumWageJan1=p['mzp'], subsistenceMinimumJan1=p['pm'], incomeLimits={str(k): x for k, x in p['L'].items()},
                                    militaryLevyForSingleTaxPayers=p['levy']),
                    source=SOURCES, unverified=UNVERIFIED,
                    notes=("Same input objects in every version (E4): expected outputs differ only where the version's parameters or structure differ. "
                           + ("2024 has no militaryLevy output (structural change in 2025). Known limitation: the validator compares expected keys only, so a 2025+ table evaluated against 2024 tests does not flag the extra militaryLevy output. " if decision == 'fop-obligations' else "")
                           + ("2024 text mentions the 1 April 2024 minimum wage (UAH 8 000) as a distractor without saying it is irrelevant; the rule itself says limits and rates use the value set on 1 January (7 100). " if v == '2024' else "")),
                    referenceDmn="Human reference written 2026-10-17 in Cowork; checked with a Python re-implementation of the FEEL subset; pending Camunda confirmation (StrictValidator).",
                    createdBy="Cowork 2026-10-17")
        open(os.path.join(d, 'meta.json'), 'w').write(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
        print(decision, v, len(gt))
