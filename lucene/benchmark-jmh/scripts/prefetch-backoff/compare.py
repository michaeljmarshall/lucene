#!/usr/bin/env python3
"""Compare JMH JSON results: compare.py <results-dir> -> markdown tables, main vs branch per scenario."""
import glob
import json
import os
import re
import sys

results = sys.argv[1] if len(sys.argv) > 1 else "results"


def load(path):
    out = {}
    for r in json.load(open(path)):
        name = r["benchmark"].split(".")[-1]
        params = ",".join(f"{k}={v}" for k, v in sorted(r.get("params", {}).items()))
        key = f"{name}" + (f" [{params}]" if params else "")
        out[key] = (r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"], r["primaryMetric"]["scoreUnit"])
    return out


def sort_key(k):
    m = re.search(r"_T(\d+)", k)
    return (re.sub(r"_T\d+.*", "", k), int(m.group(1)) if m else 0, k)


for scen in ("hot", "pressure", "cold"):
    for harness in ("randomread", "storedfields"):
        main_f = os.path.join(results, f"main-{scen}-{harness}.json")
        br_f = os.path.join(results, f"branch-{scen}-{harness}.json")
        if not (os.path.exists(main_f) and os.path.exists(br_f)):
            continue
        main, br = load(main_f), load(br_f)
        unit = next(iter(main.values()))[2]
        print(f"\n### {scen} / {harness}  ({unit}, higher is better)\n")
        print("| benchmark | main | branch | delta |")
        print("|---|---:|---:|---:|")
        for k in sorted(set(main) & set(br), key=sort_key):
            m, me, _ = main[k]
            b, be, _ = br[k]
            d = (b - m) / m * 100 if m else float("nan")
            print(f"| {k} | {m:,.1f} ± {me:,.1f} | {b:,.1f} ± {be:,.1f} | {d:+.1f}% |")
