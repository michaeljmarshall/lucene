#!/usr/bin/env bash
# Runs the whole matrix for both variants: hot, pressure, cold, then hot again as a repeat sample
# (into results-hot2/). One log, results/all.log. Extra arguments go to JMH (e.g. -f 1).
#   tmux new -d -s bench "bash /opt/bench/run-all.sh"
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p results
{
  echo "== run-all start $(date -u +%FT%TZ)"
  for s in hot pressure cold; do
    for v in main branch; do bash run-bench.sh "$v" "$s" "$@" || echo "!! FAILED $v $s"; done
  done
  for v in main branch; do BENCH_OUT=$PWD/results-hot2 bash run-bench.sh "$v" hot "$@" || echo "!! FAILED $v hot2"; done
  echo "== ALL DONE $(date -u +%FT%TZ)"
} 2>&1 | tee -a results/all.log
