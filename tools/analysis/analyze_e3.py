import json, sys, collections, re, math, os
from math import comb
R = sys.argv[1]
def wilson(k, n, z=1.959964):
    p = k/n; d = 1+z*z/n; c = (p+z*z/(2*n))/d; h = z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/d
    return max(0, c-h)*100, min(1, c+h)*100
def fisher_greater(a, n1, c, n2):  # P(X>=a) , group1 a/n1 vs group2 c/n2
    K = a+c; N = n1+n2
    return sum(comb(n1, x)*comb(n2, K-x) for x in range(a, min(n1, K)+1) if 0 <= K-x <= n2)/comb(N, K)
def fisher_two(a, n1, c, n2):
    K = a+c; N = n1+n2; pr = lambda x: comb(n1, x)*comb(n2, K-x)/comb(N, K)
    p0 = pr(a); return sum(pr(x) for x in range(max(0, K-n2), min(n1, K)+1) if pr(x) <= p0*(1+1e-9))
def types(app):
    g = lambda k: app.get(k)
    fixed = g("fixedPreference") is True or g("rateSensitivity") == "high"
    arm = all(v is not None for v in (g("horizonYears"), g("ltv"), g("creditScore"))) and g("horizonYears") <= 7 and g("incomeStability") == "stable" and g("ltv") <= 80 and g("creditScore") >= 720
    man = (g("creditScore") is not None and g("creditScore") < 640) or (g("dti") is not None and g("dti") > 50) or (g("ltv") is not None and g("ltv") > 95)
    t = []
    if man and fixed: t.append("MR/Fixed")
    if man and arm: t.append("MR/ARM")
    if fixed and arm: t.append("Fixed/ARM")
    return t
def load(run):
    f = os.path.join(R, run+".jsonl"); d = dict(n=0, out=collections.Counter(), wrong=0, lit=0, dom_ok=0, src=collections.Counter(), att=0, dur=0, tok=0, cost=0, closed=collections.Counter(), hp=collections.Counter(), err=0,
        flagcycles=collections.Counter(), suites=[], overlap_in_accepted=0, pat=collections.Counter(), unflagged_overlap=0, flagnotes=[])
    att = {}
    for l in open(f):
        e = json.loads(l)
        if e["event"] == "attempt":
            att.setdefault(e["cycle"], []).append(e)
            for c in e.get("closedConflicts") or []: d["closed"][c["reason"]] += 1
        if e["event"] == "cycle_finished":
            if e["outcome"] == "ERROR": d["err"] += 1; continue
            d["n"] += 1; d["out"][e["outcome"]] += 1; d["att"] += e["attempts"]; d["dur"] += e["durationMs"]; d["cost"] += e.get("costUsd") or 0
            d["tok"] += sum(v["input"]+v["output"] for v in e["tokens"].values())
            g = e["groundTruth"]["counts"]; a = (e.get("groundTruthAlt") or {}).get("counts", {})
            last = att[e["cycle"]][-1]; hp = re.findall(r'hitPolicy="([^"]+)"', last.get("dmnXml") or ""); d["hp"][hp[0] if hp else None] += 1
            d["suites"].append(last["testSuiteSize"])
            d["pat"][(e["outcome"], g.get("PASS", 0), a.get("PASS", 0) if a else None, hp[0] if hp else None)] += 1
            if e.get("ambiguitySource"): d["src"][e["ambiguitySource"]] += 1
            if e["outcome"] == "ACCEPTED":
                if g.get("PASS") != 45: d["wrong"] += 1
                else: d["dom_ok"] += 1
                if a and a.get("PASS") == 45: d["lit"] += 1
                ov = [t for t in last["testSuite"] if types((t.get("object") or {}).get("application") or t.get("object") or {})]
                if any("MR/" in x for t in ov for x in types((t.get("object") or {}).get("application") or t.get("object") or {})): d["overlap_in_accepted"] += 1
            if e["outcome"] == "AMBIGUOUS":
                ts = set()
                for a_ in att[e["cycle"]]:
                    for fc in a_.get("flaggedCases") or []:
                        ts.update(types((fc.get("input") or {}).get("application") or {}) or ["other"]); d["flagnotes"].append((fc.get("note") or "")[:160])
                for t in ts: d["flagcycles"][t] += 1
    return d
arms = ["b0", "b1", "v1", "a1"]
for series, pre in (("E3 (gemma tester)", "e3-"), ("E3-S (Sonnet tester)", "e3s-")):
    print("=====", series); D = {}
    for text in ("disambiguated", "ambiguous"):
        for arm in arms:
            d = D[(arm, text)] = load(f"{pre}{arm}-{text}"); n = d["n"]; lo, hi = wilson(d["wrong"], n); amb = d["out"].get("AMBIGUOUS", 0)
            print(f"{arm} {text:14s} n={n} acc={d['out'].get('ACCEPTED',0)} wrong={d['wrong']} lit45={d['lit']} amb={amb} {dict(d['src'])} budget={d['out'].get('REJECTED_BUDGET',0)} err={d['err']} FAR={100*d['wrong']/n:.0f}% [{lo:.1f};{hi:.1f}] | att={d['att']/n:.2f} t={d['dur']/n/1000:.0f}s tok={d['tok']/n/1000:.1f}k ${d['cost']:.2f} suite={sum(d['suites'])/len(d['suites']):.1f} | closed={dict(d['closed'])} | hp={dict(d['hp'])} | overlapMR-in-accepted={d['overlap_in_accepted']}")
    A = lambda arm, t: D[(arm, t)]
    w = lambda a, t: A(a, t)["wrong"]; am = lambda a, t: A(a, t)["out"].get("AMBIGUOUS", 0)
    print("H2  FAR(B0)>FAR(B1) amb:", w("b0", "ambiguous"), w("b1", "ambiguous"), "p=%.4g" % fisher_greater(w("b0", "ambiguous"), 50, w("b1", "ambiguous"), 50))
    print("H3  V1 amb rate amb>clear:", am("v1", "ambiguous"), am("v1", "disambiguated"), "p=%.4g" % fisher_greater(am("v1", "ambiguous"), 50, am("v1", "disambiguated"), 50))
    print("H3a A1 amb rate amb>clear:", am("a1", "ambiguous"), am("a1", "disambiguated"), "p=%.4g" % fisher_greater(am("a1", "ambiguous"), 50, am("a1", "disambiguated"), 50))
    print("H5  FAR(A1)<FAR(B1) amb:", w("a1", "ambiguous"), w("b1", "ambiguous"), "p=%.4g" % fisher_greater(w("b1", "ambiguous"), 50, w("a1", "ambiguous"), 50))
    tot = sum(w(a, "disambiguated") for a in arms); print("H4  clear FAR total", tot, "/200 CI", "[%.1f;%.1f]" % wilson(tot, 200), "per arm", "[%.1f;%.1f]" % wilson(0, 50))
    d = A("a1", "ambiguous"); print("A1 amb recall", am("a1", "ambiguous"), "CI [%.1f;%.1f]" % wilson(am("a1", "ambiguous"), 50), "flag types (cycles):", dict(d["flagcycles"]))
    d = A("a1", "disambiguated"); print("A1 clear escal CI [%.1f;%.1f]" % wilson(am("a1", "disambiguated"), 50))
    if pre == "e3s-":
        d = load("e3s-b0-ambiguous-nohp"); n = d["n"]
        print("NOHP: wrong", d["wrong"], "CI [%.1f;%.1f]" % wilson(d["wrong"], n), "lit45", d["lit"], "dom45", d["dom_ok"], "att %.2f" % (d["att"]/n), "t %.0fs" % (d["dur"]/n/1000), "tok %.1fk" % (d["tok"]/n/1000), "$%.2f" % d["cost"], "suite %.1f" % (sum(d["suites"])/n))
        for k, v in d["pat"].most_common(): print("   ", v, k)
        print("   closed", dict(d["closed"]), "final hp", dict(d["hp"]))
        print("   two-sided Fisher vs e3s-b0-ambiguous (50/50): p=%.4g" % fisher_two(d["wrong"], 50, w("b0", "ambiguous"), 50))
        print("   lit45: 32/50 vs 50/50 two-sided p=%.4g" % fisher_two(d["lit"], 50, A("b0", "ambiguous")["lit"], 50), "CI lit [%.1f;%.1f]" % wilson(d["lit"], 50), "CI dom [%.1f;%.1f]" % wilson(d["dom_ok"], 50))
        print("total cost E3-S $%.2f" % (sum(x["cost"] for x in D.values())+d["cost"]))
