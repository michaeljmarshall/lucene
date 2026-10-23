#!/usr/bin/env python3
"""compare-sweep.py <stage-dir>... -> per scenario/harness table: delta vs main for each variant.
Several stage dirs (e.g. hotA hotB) are averaged per benchmark; scores shown as % vs main."""
import glob, json, os, re, sys

VARIANTS = ["main", "branch", "skip128", "skip256", "skip512"]
dirs = sys.argv[1:] or ["."]


def load(path):
    out = {}
    for r in json.load(open(path)):
        name = r["benchmark"].split(".")[-1]
        params = ",".join(f"{k}={v}" for k, v in sorted(r.get("params", {}).items()))
        out[name + (f" [{params}]" if params else "")] = r["primaryMetric"]["score"]
    return out


def sort_key(k):
    m = re.search(r"_T(\d+)", k)
    return (re.sub(r"_T\d+.*", "", k), int(m.group(1)) if m else 0, k)


for scen in ("hot", "pressure", "cold"):
    for harness in ("randomread", "storedfields"):
        data = {}  # variant -> bench -> [scores]
        for v in VARIANTS:
            for d in dirs:
                p = os.path.join(d, f"{v}-{scen}-{harness}.json")
                if os.path.exists(p):
                    for k, s in load(p).items():
                        data.setdefault(v, {}).setdefault(k, []).append(s)
        if "main" not in data:
            continue
        vs = [v for v in VARIANTS if v in data]
        n = max(len(x) for x in data["main"].values())
        print(f"\n### {scen} / {harness}  (% vs main, mean of {n} run(s); main in ops/ms)\n")
        print("| benchmark | main | " + " | ".join(v for v in vs if v != "main") + " |")
        print("|---|---:|" + "---:|" * (len(vs) - 1))
        for k in sorted(data["main"], key=sort_key):
            m = sum(data["main"][k]) / len(data["main"][k])
            cells = []
            for v in vs:
                if v == "main":
                    continue
                xs = data[v].get(k)
                cells.append(f"{(sum(xs) / len(xs) - m) / m * 100:+.1f}%" if xs and m else "-")
            print(f"| {k} | {m:,.1f} | " + " | ".join(cells) + " |")
