#!/usr/bin/env bash
# One-time setup on the benchmark VM. Installs JDK 25 to /opt/jdk-25, copies this kit to /opt/bench
# and bind-mounts a data dir on the local NVMe at /data, so nothing JMH records depends on the user
# or the mount layout.
#   usage: setup-box.sh [data-mount]   (default: the largest non-root mount)
set -euo pipefail
KIT=$(cd "$(dirname "$0")" && pwd)

if [ ! -x /opt/jdk-25/bin/java ]; then
  echo "== installing Temurin 25 to /opt/jdk-25"
  curl -sSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk.tar.gz
  sudo mkdir -p /opt/jdk-25 && sudo tar -xzf /tmp/jdk.tar.gz -C /opt/jdk-25 --strip-components=1 && rm /tmp/jdk.tar.gz
fi
/opt/jdk-25/bin/java -version

if [ -L /opt/bench ]; then sudo rm /opt/bench; fi
if [ -e /opt/bench ]; then
  echo "== /opt/bench exists; refreshing scripts only (results kept)"
  sudo cp "$KIT"/*.sh "$KIT"/*.py /opt/bench/
else
  echo "== copying kit to /opt/bench"
  sudo cp -a "$KIT" /opt/bench
fi
sudo chown -R "$USER" /opt/bench

MOUNT=${1:-$(df -x tmpfs -x devtmpfs -x overlay --output=target,size -B1 | tail -n +2 | grep -v '^/ ' | sort -k2 -n | tail -1 | awk '{print $1}')}
[ -n "$MOUNT" ] || { echo "no data mount found; pass one as the first argument"; exit 1; }
sudo mkdir -p "$MOUNT/bench" && sudo chown "$USER" "$MOUNT/bench"
if [ -L /data ]; then sudo rm /data; fi
sudo mkdir -p /data
mountpoint -q /data || sudo mount --bind "$MOUNT/bench" /data

echo "== box"
nproc; awk '/MemTotal/ {printf "MemTotal %.1f GiB\n", $2/1024/1024}' /proc/meminfo
lscpu | grep -E "Model name|Thread|Core|Socket" | sed 's/  */ /g'
df -h "$MOUNT" | tail -1
lsblk -d -o NAME,SIZE,ROTA,MODEL | grep -v loop
DEV=$(df --output=source "$MOUNT" | tail -1)
echo "data device: $DEV  readahead: $(sudo blockdev --getra "$DEV") sectors  fs: $(df --output=fstype "$MOUNT" | tail -1)"
echo "other java processes: $(pgrep -c java || true)   (should be 0)"
echo "sudo drop_caches: $(sudo -n bash -c 'echo 3 > /proc/sys/vm/drop_caches' && echo ok || echo FAILED)"
echo
echo "kit: /opt/bench    data: /data (bind of $MOUNT/bench)    java: /opt/jdk-25"
echo "start with:  tmux new -d -s bench 'bash /opt/bench/run-all.sh'"
