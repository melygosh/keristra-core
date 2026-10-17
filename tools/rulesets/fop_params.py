P = {
 '2024': dict(mzp=7100, pm=3028, mzp_note_uk=' Довідково: з 1 квітня 2024 року мінімальна заробітна плата становить 8 000 грн.',
              mzp_note_en=' For reference: from 1 April 2024 the minimum wage is UAH 8,000.', levy=False),
 '2025': dict(mzp=8000, pm=3028, mzp_note_uk='', mzp_note_en='', levy=True),
 '2026': dict(mzp=8647, pm=3328, mzp_note_uk='', mzp_note_en='', levy=True),
}
for v,p in P.items():
    p['L']={1:167*p['mzp'],2:834*p['mzp'],3:1167*p['mzp']}
    p['tax1']=round(p['pm']*0.10,2); p['tax2']=round(p['mzp']*0.20,2); p['levy12']=round(p['mzp']*0.10,2)
def fmt(n):  # 1444049 -> "1 444 049"
    s=f"{n:,.2f}".rstrip('0').rstrip('.') ; return s.replace(',',' ')
def fmt_en(n):
    s=f"{n:,.2f}".rstrip('0').rstrip('.'); return s
