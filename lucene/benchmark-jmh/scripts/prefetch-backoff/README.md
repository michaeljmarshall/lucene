# Prefetch backoff benchmarks (apache/lucene #16145)

Compares `main` (power-of-two backoff) against the branch (bounded backoff, N=1024, gap 16..256)
using neoremind's JMH harnesses from #16279 (`RandomReadIOBenchmark`) and commit `13c55fe`
(`StoredFieldsPrefetchBenchmark`). Both jar sets were built from the same tree with only
`lucene/core` differing.

```
main/          JMH jar + deps built at b304eeae02 (upstream/main)
branch/        JMH jar + deps built at 4721c753de (main + b26ccc16bb)
setup-box.sh   JDK 25 -> /opt/jdk-25, this dir -> /opt/bench, NVMe data dir bind-mounted at /data
run-all.sh     hot, pressure, cold for both variants, then hot again -> results/, results-hot2/
run-bench.sh   one variant x one scenario (what run-all.sh calls)
compare.py     results dir -> markdown tables of main vs branch
iterations.py  results dir [scenario] -> per-iteration tables (shows warm-up shape, bimodal states)
results.md     the two above rendered for results/ and results-hot2/
```

## Machine

Any Linux VM with a local NVMe SSD and passwordless `sudo` (for `drop_caches`), with nothing else
running on it. The results here are from a GCP `n2-custom-16-32768` (Cascade Lake, 8 cores / 16
threads, 31 GiB) with two local SSDs in RAID-0, ext4, Ubuntu 22.04.

## Run

```sh
scp -r . user@$IP:bench
ssh user@$IP 'bash ~/bench/setup-box.sh'
ssh user@$IP "tmux new -d -s bench 'bash /opt/bench/run-all.sh'"
ssh user@$IP 'tail -3 /opt/bench/results/all.log'      # progress; ends with "== ALL DONE"
```

About 3 hours. The first pressure run is quiet for ~10 minutes while it builds the 10M-doc index.
Pass `-f 1` to `run-all.sh` for a faster single-fork pass.

## Scenarios

- `hot`: 1 GiB file / 1M docs, fully cached.
- `pressure`: RAM+8 GiB file and a matching doc count, so the working set exceeds RAM.
- `cold`: 4 GiB / 1M docs with `drop_caches` before every iteration.

`hot` is run again at the end as a repeat sample.

Before each harness in `hot` and `pressure`, the page cache is dropped and that harness's data
is read once sequentially, so both variants start from the same state regardless of what ran
before: fully resident for `hot`, the most recently read ~RAM worth for `pressure`. Without this,
the cache state carried over from the previous run (e.g. the random-read file staying on the
kernel's active list while the freshly built index is evicted) and the two variants measured
different residency.

Benchmarks: `mmap*_T{01,04,08,16}` (no prefetch, the floor), `mmap*BatchedPrefetch_T*` (NORMAL and
RANDOM advice), and all of `StoredFieldsPrefetchBenchmark` (topK 10/100, T1/T8, including
`prefetchOnly`, which isolates the cost of the `prefetch()` call). Each table's no-prefetch rows
run identical code in both variants and show the noise floor.

The random-read data file is written with `dd if=/dev/zero` rather than `/dev/urandom` as the
harness suggests; page-cache behaviour is the same and it is much faster for the 39 GiB file.

## Compare

```sh
rsync -av user@$IP:/opt/bench/results/ results/
rsync -av user@$IP:/opt/bench/results-hot2/ results-hot2/
python3 compare.py results
python3 compare.py results-hot2
python3 iterations.py results cold     # per-iteration view, e.g. the warm-up transient
```
