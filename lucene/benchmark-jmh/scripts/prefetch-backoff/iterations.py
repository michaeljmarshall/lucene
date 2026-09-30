#!/usr/bin/env python3
"""Per-iteration scores from JMH JSON: iterations.py <results-dir> [scenario] -> markdown tables.

One row per benchmark and variant, one column per measurement iteration, forks separated by '|'.
Useful where the mean hides the shape, e.g. the cold-cache warm-up or a bimodal backoff state.
"""
import glob
import json
import os
import re
import sys

results = sys.argv[1] if len(sys.argv) > 1 else "results"
only = sys.argv[2] if len(sys.argv) > 2 else None


def sort_key(k):
    m = re.search(r"_T(\d+)", k)
    return (re.sub(r"_T\d+.*", "", k), int(m.group(1)) if m else 0, k)


for scen in ("hot", "pressure", "cold"):
    if only and scen != only:
        continue
    for harness in ("randomread", "storedfields"):
        files = {v: os.path.join(results, f"{v}-{scen}-{harness}.json") for v in ("main", "branch")}
        if not all(os.path.exists(f) for f in files.values()):
            continue
        rows = {}
        unit = None
        for v, f in files.items():
            for r in json.load(open(f)):
                name = r["benchmark"].split(".")[-1]
                params = ",".join(f"{k}={val}" for k, val in sorted(r.get("params", {}).items()))
                key = name + (f" [{params}]" if params else "")
                rows.setdefault(key, {})[v] = r["primaryMetric"]["rawData"]
                unit = r["primaryMetric"]["scoreUnit"]
        n_iter = len(next(iter(rows.values()))["main"][0])
        print(f"\n### {scen} / {harness}: per-iteration scores ({unit}; forks separated by |)\n")
        print("| benchmark | variant | " + " | ".join(f"iter {i + 1}" for i in range(n_iter)) + " |")
        print("|---|---|" + "---:|" * n_iter)
        for key in sorted(rows, key=sort_key):
            for v in ("main", "branch"):
                forks = rows[key][v]
                cells = [" \\| ".join(f"{fk[i]:,.1f}" for fk in forks) for i in range(n_iter)]
                print(f"| {key} | {v} | " + " | ".join(cells) + " |")
