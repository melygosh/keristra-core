import os
import json, re, os, xml.etree.ElementTree as ET
from decimal import Decimal, ROUND_HALF_EVEN
NS={'d':'https://www.omg.org/spec/DMN/20191111/MODEL/'}
ROOT=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'rulesets')
def load(p):
    t=ET.parse(p).getroot().find('.//d:decisionTable',NS)
    ins=[i.find('d:inputExpression/d:text',NS).text.strip() for i in t.findall('d:input',NS)]
    outs=[o.get('name') for o in t.findall('d:output',NS)]
    rules=[([(e.find('d:text',NS).text or '').strip() for e in r.findall('d:inputEntry',NS)],[(e.find('d:text',NS).text or '').strip() for e in r.findall('d:outputEntry',NS)]) for r in t.findall('d:rule',NS)]
    return ins,outs,rules
def get(o,path):
    for k in path.split('.'): o=o.get(k) if isinstance(o,dict) else None
    return o
def lit(s):
    s=s.strip()
    if s.startswith('"'): return s.strip('"')
    if s in('true','false'): return s=='true'
    return Decimal(s)
def test(e,v):
    if e=='': return True
    if v is None: return False
    m=re.fullmatch(r'not\((.*)\)',e)
    if m: return not test(m.group(1),v)
    m=re.fullmatch(r'(<=|>=|<|>)\s*(-?[\d.]+)',e)
    if m:
        n=Decimal(m.group(2)); x=Decimal(str(v)); return {'<':x<n,'<=':x<=n,'>':x>n,'>=':x>=n}[m.group(1)]
    parts=[x.strip() for x in e.split(',')]
    for pt in parts:
        L=lit(pt)
        if isinstance(L,Decimal):
            if not isinstance(v,bool) and Decimal(str(v))==L: return True
        elif v==L: return True
    return False
def out(expr,obj):
    m=re.fullmatch(r'decimal\(fop\.(\w+) \* ([\d.]+), 2\)',expr)
    if m:
        x=get(obj,'fop.'+m.group(1))
        return None if x is None else float((Decimal(str(x))*Decimal(m.group(2))).quantize(Decimal('0.01'),ROUND_HALF_EVEN))
    L=lit(expr); return float(L) if isinstance(L,Decimal) else L
def evaluate(dmnpath,gt):
    ins,outs,rules=load(dmnpath); fails=[]
    for c in gt:
        res=None
        for ents,oe in rules:
            if all(test(e,get(c['object'],p)) for e,p in zip(ents,ins)):
                res=[{n:out(x,c['object']) for n,x in zip(outs,oe)}]; break
        exp=c['expected']
        ok = (res is None and not exp) or (res is not None and exp and all(res[0].get(k)==(float(v) if isinstance(v,(int,float)) and not isinstance(v,bool) else v) for k,v in exp[0].items()))
        if not ok: fails.append((c['id'],c['category'],exp,res))
    return fails
for dec in ('fop-eligibility','fop-obligations'):
    for v in ('2024','2025','2026'):
        d=f'{ROOT}/{dec}/{v}'; gt=json.load(open(f'{d}/ground-truth.json'))
        f=evaluate(f'{d}/reference.dmn',gt); print(dec,v,f'{len(gt)-len(f)}/{len(gt)}',f[:3])
# E4: cross-version — reference of version a against ground truth of version b
print('cross-version failures (DMN version -> GT version):')
for dec in ('fop-eligibility','fop-obligations'):
    for a in ('2024','2025','2026'):
        row=[]
        for b in ('2024','2025','2026'):
            gt=json.load(open(f'{ROOT}/{dec}/{b}/ground-truth.json')); row.append(len(evaluate(f'{ROOT}/{dec}/{a}/reference.dmn',gt)))
        print(' ',dec,a,'->',dict(zip(('2024','2025','2026'),row)))
