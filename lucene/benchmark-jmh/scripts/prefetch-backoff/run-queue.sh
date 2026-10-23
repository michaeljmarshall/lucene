#!/usr/bin/env bash
# Runs the SKIP-sweep queue, most important stages first so partial results are useful.
#   tmux new -d -s bench3 'bash /opt/bench/run-queue.sh'
# Output: results-v2/<stage>/<variant>-<scenario>-{randomread,storedfields}.json, log results-v2/queue.log
set -uo pipefail
cd "$(dirname "$0")"
VARIANTS=(main branch skip128 skip256 skip512)
REV=(skip512 skip256 skip128 branch main)
mkdir -p results-v2
stage() { # name scenario order(fwd|rev) [jmh args]
  local name=$1 scen=$2 order=$3; shift 3
  local list=("${VARIANTS[@]}"); [ "$order" = rev ] && list=("${REV[@]}")
  for v in "${list[@]}"; do
    BENCH_OUT=$PWD/results-v2/$name bash run-bench.sh "$v" "$scen" "$@" || echo "!! FAILED $v $scen ($name)"
  done
  echo "== STAGE DONE $name $(date -u +%FT%TZ)"
}
{
  echo "== queue start $(date -u +%FT%TZ)"
  stage hotA      hot      fwd
  stage pressureA pressure fwd -f 1
  stage hotB      hot      rev
  stage pressureB pressure rev -f 1
  stage coldA     cold     fwd -f 1
  echo "== QUEUE DONE $(date -u +%FT%TZ)"
} 2>&1 | tee -a results-v2/queue.log
