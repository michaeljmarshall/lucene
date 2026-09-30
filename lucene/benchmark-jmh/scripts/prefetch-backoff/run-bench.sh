#!/usr/bin/env bash
# Runs neoremind's read-IO and stored-fields JMH harnesses for one Lucene variant and one scenario.
#
#   usage: run-bench.sh <main|branch> <hot|pressure|cold> [extra JMH args, e.g. -f 1]
#
# Before each harness the page cache is dropped and that harness's data is read once, sequentially,
# so both variants start from the same state: fully resident for hot, the most recently read ~RAM
# worth for pressure. Cold drops the cache before every iteration instead (the harness does it).
#
# Defaults assume setup-box.sh has run (override with env):
#   BENCH_ROOT  /opt/bench        -> main/ branch/ (jar dirs), results/
#   BENCH_DATA  /data             -> on the local NVMe
#   BENCH_OUT   $BENCH_ROOT/results
#   BENCH_ONLY  both | randomread | storedfields
#   JAVA        /opt/jdk-25/bin/java
set -euo pipefail

VARIANT=$1; SCEN=$2; shift 2
ROOT=${BENCH_ROOT:-/opt/bench}
DATA=${BENCH_DATA:-/data}
JAVA=${JAVA:-/opt/jdk-25/bin/java}
JAR=$(ls "$ROOT/$VARIANT"/lucene-benchmark-jmh-*.jar)
OUT=${BENCH_OUT:-$ROOT/results}
ONLY=${BENCH_ONLY:-both}
mkdir -p "$OUT" "$DATA"

RAM_GB=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)
case $SCEN in
  hot)      FILE_MB=1024;                    SF_DOCS=1000000;                                     DROP=false ;;
  pressure) FILE_MB=$(( (RAM_GB + 8) * 1024 )); SF_DOCS=$(( (RAM_GB + 8) * 1024 * 1024 * 1024 / 4096 )); DROP=false ;;
  cold)     FILE_MB=4096;                    SF_DOCS=1000000;                                     DROP=true ;;
  *) echo "unknown scenario: $SCEN (hot|pressure|cold)"; exit 2 ;;
esac
FILE=$DATA/pread-bench-${FILE_MB}mb.dat
INDEX=$DATA/sf-index-$SF_DOCS
SF_PROPS="-Dbench.indexPath=$INDEX -Dbench.numDocs=$SF_DOCS -Dbench.docSizeBytes=4096 -Dbench.readAdvice=RANDOM -Dbench.prefetchWindow=16"

echo "== $VARIANT / $SCEN  ram=${RAM_GB}G file=${FILE_MB}MB sfDocs=$SF_DOCS drop=$DROP  $(date -u +%FT%TZ)"
echo "   jar=$JAR"
"$JAVA" -version 2>&1 | head -1

# Drop the page cache, then read the given paths once in a fixed order.
prime() {
  if [ "$DROP" = true ]; then return; fi
  echo "   priming: drop caches, sequential read of $*"
  sudo -n bash -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
  find "$@" -type f -print0 | sort -z | xargs -0 cat > /dev/null
}

if [ "$ONLY" != storedfields ]; then
  # The read-IO harness expects the file to exist. Write real blocks (not fallocate: unwritten
  # extents read as zeros with no device I/O, which would make misses free).
  if [ ! -f "$FILE" ] || [ "$(stat -c %s "$FILE")" -lt $(( FILE_MB * 1024 * 1024 )) ]; then
    echo "   creating $FILE"
    dd if=/dev/zero of="$FILE" bs=1M count="$FILE_MB" conv=fsync status=none
  fi
  prime "$FILE"
  # JMH forks: pass config as -D via -jvmArgsAppend so the forked JVMs see it.
  "$JAVA" -jar "$JAR" 'RandomReadIOBenchmark\.mmap(Random)?(BatchedPrefetch)?_T' \
    -jvmArgsAppend "-Dbench.file=$FILE -Dbench.fileSizeMB=$FILE_MB -Dbench.dropPageCache=$DROP" \
    -rf json -rff "$OUT/$VARIANT-$SCEN-randomread.json" -foe true "$@"
fi

if [ "$ONLY" != randomread ]; then
  # Build the index up front (a throwaway 1s run of the harness) so priming sees the finished files.
  if [ ! -d "$INDEX" ]; then
    echo "   building $INDEX"
    "$JAVA" -jar "$JAR" 'StoredFieldsPrefetchBenchmark\.retrieveNoPrefetch_T01' -p topK=10 \
      -f 1 -wi 0 -i 1 -r 1 -jvmArgsAppend "$SF_PROPS" > /dev/null
  fi
  prime "$INDEX"
  "$JAVA" -jar "$JAR" 'StoredFieldsPrefetchBenchmark\.' \
    -jvmArgsAppend "$SF_PROPS -Dbench.dropPageCache=$DROP" \
    -rf json -rff "$OUT/$VARIANT-$SCEN-storedfields.json" -foe true "$@"
fi

echo "== done $VARIANT / $SCEN  $(date -u +%FT%TZ)"
