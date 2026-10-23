# Prefetch backoff benchmarks (apache/lucene #16145)

Compares `main` against the PR branch using neoremind's JMH harnesses from #16279
(`RandomReadIOBenchmark`) and commit `13c55fe` (`StoredFieldsPrefetchBenchmark`), and sweeps the
gap between page-cache probes (`PrefetchBackoff.SKIP`) once a file looks fully cached.

There are two rounds of results:

- `results-v1.md` (`results/`, `results-hot2/`): `main` at `b304eeae02` (power-of-two backoff) against
  the branch at `4721c753de` (bounded backoff, N=1024, gap 16..256). Older code; kept for reference.
- `results.md` (`results-v2/`): the current design, described below.

## Variants (round 2)

All jar sets were built from the same tree with only `lucene/core` differing, plus the three harness
files from this branch.

```
main/      12d316cb85  merge-base of the PR branch with upstream/main
branch/    37c755bf3e  PR head: N=1024, then probe every 64th call per input (SKIP = 64)
skip128/   branch with PrefetchBackoff.SKIP = 128
skip256/   branch with PrefetchBackoff.SKIP = 256
skip512/   branch with PrefetchBackoff.SKIP = 512
```

Each variant is `./gradlew :lucene:benchmark-jmh:assemble` in a checkout of that commit (with the
`SKIP` constant edited for the `skipNNN` variants); the jar directory is
`lucene/benchmark-jmh/build/benchmarks/`. The jars are not committed.

## Files

```
setup-box.sh      JDK 25 -> /opt/jdk-25, this dir -> /opt/bench, NVMe data dir bind-mounted at /data
run-bench.sh      one variant x one scenario
run-queue.sh      round 2: all variants, hot/pressure twice (second in reverse order), then cold
run-all.sh        round 1: hot, pressure, cold for main and branch, then hot again
compare-sweep.py  stage dir(s) -> % vs main per variant; several dirs are averaged
compare.py        round 1: results dir -> main vs branch tables with error bars
iterations.py     round 1: results dir [scenario] -> per-iteration tables
box-info.txt      CPU, memory, disks and readahead of the machine used for round 2
results-v2/       JSON per stage (hotA, pressureA, hotB, pressureB, coldA) and queue.log
```

## Machine

Any Linux VM with a local NVMe SSD and passwordless `sudo` (for `drop_caches`), with nothing else
running on it. Round 2 ran on a GCP n2 `custom-16-32768` (2.80 GHz Xeon, 8 cores / 16 threads,
31 GiB) with two local NVMe SSDs in RAID-0, ext4, Ubuntu 22.04, kernel 6.8, readahead 128 KiB
(the default). See `box-info.txt`.

## Run

```sh
scp -r . user@$IP:bench                       # plus the variant jar dirs, see above
ssh user@$IP 'bash ~/bench/setup-box.sh'
ssh user@$IP "tmux new -d -s bench 'bash /opt/bench/run-queue.sh'"
ssh user@$IP 'grep -E "== (STAGE|QUEUE)|!! " /opt/bench/results-v2/queue.log | tail'
```

Round 2 took about 5.4 hours: hot about 87 minutes per stage (17 per variant), pressure about
50 minutes per stage (10 per variant, `-f 1`) and cold about 47 minutes (`-f 1`). The first
pressure run is quiet for ~10 minutes while it builds the 10M-doc index.

## Scenarios

- `hot`: 1 GiB file / 1M docs, fully cached.
- `pressure`: RAM+8 GiB file and a matching doc count, so the working set exceeds RAM.
- `cold`: 4 GiB / 1M docs with `drop_caches` before every iteration.

Before each harness in `hot` and `pressure`, the page cache is dropped and that harness's data is
read once sequentially, so every variant starts from the same state: fully resident for `hot`, the
most recently read ~RAM worth for `pressure`. The `B` stages run the variants in reverse order to
cancel ordering effects (e.g. `main` running first and being slower in a stage).

Benchmarks: `mmap*_T{01,04,08,16}` (no prefetch, the floor), `mmap*BatchedPrefetch_T*` (NORMAL and
RANDOM advice), and all of `StoredFieldsPrefetchBenchmark` (topK 10/100, T1/T8, including
`prefetchOnly`, which only issues `prefetch()` and never reads). Each table's no-prefetch rows run
identical code in every variant and show the noise floor.

Reading `prefetchOnly`: on a hot file it measures the per-call cost of the sampling, since every
prefetch is a no-op. Under pressure and cold it mostly measures how many `madvise(WILLNEED)` calls a
variant really issues: `main` stops probing after a short run of hits and mostly does not, the branch
keeps probing until a file is almost fully cached. The `retrievePrefetchThenRead` rows are where
that I/O pays off.

The random-read data file is written with `dd if=/dev/zero` rather than `/dev/urandom` as the
harness suggests; page-cache behaviour is the same and it is much faster for the 39 GiB file.

## Compare

```sh
rsync -av user@$IP:/opt/bench/results-v2/ results-v2/
python3 compare-sweep.py results-v2/hotA results-v2/hotB
python3 compare-sweep.py results-v2/pressureA results-v2/pressureB
python3 compare-sweep.py results-v2/coldA
```
