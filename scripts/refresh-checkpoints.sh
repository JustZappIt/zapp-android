#!/usr/bin/env bash
# Refresh the bundled Zcash checkpoints that a wallet's scan starts from.
#
# Checkpoints ship inside the SDK AAR, frozen at whatever height that SDK was
# cut. The chain keeps moving, so every week that passes adds blocks that a
# newly restored wallet — and every freshly minted Zpacket — has to scan from
# scratch. A card minted above the newest bundled checkpoint pays the whole gap
# again, on a phone, over gRPC.
#
# The app's own assets are merged over the library's by path, and the app
# declares no lightwallet-client dependency of its own, so files written here
# land alongside the SDK's rather than replacing them. `CheckpointTool` reads
# `co.electriccoin.zcash/checkpoint/<lowercase network>` out of assets and picks
# the highest checkpoint at or below the wallet's birthday.
#
# Usage:
#   scripts/refresh-checkpoints.sh mainnet
#   scripts/refresh-checkpoints.sh testnet
#   scripts/refresh-checkpoints.sh mainnet zec.rocks:443
#
# Requires grpcurl and python3. Needs one prior build of the app, because the
# existing checkpoint set is read out of the merged asset intermediates — that
# is the only place the SDK's own files and the app's appear together.
#
# This is a release step, not a one-off: see docs/RELEASE.md.

set -euo pipefail

readonly STRIDE=2500
# Reorg headroom. A checkpoint at a height that later gets rolled back is worse
# than no checkpoint at all, because the tree it pins never existed.
readonly TIP_MARGIN=100

network="${1:-}"
case "$network" in
    mainnet) default_endpoint="zec.rocks:443"; cross_endpoint="eu.zec.rocks:443"; expected_network="main" ;;
    testnet) default_endpoint="testnet.zec.rocks:443"; cross_endpoint=""; expected_network="test" ;;
    *) echo "usage: $0 <mainnet|testnet> [host:port]" >&2; exit 2 ;;
esac

endpoint="${2:-$default_endpoint}"
[[ -n "${2:-}" ]] && cross_endpoint=""

for tool in grpcurl python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "error: $tool is not on PATH" >&2; exit 1; }
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$repo_root/app/src/main/assets/co.electriccoin.zcash/checkpoint/$network"

# The merged set: the SDK's bundled files plus anything a previous run of this
# script already wrote. Reading only the app's own directory would restart the
# stride from whatever this script last produced and leave a hole.
merged_dir="$(find "$repo_root/app/build/intermediates/assets" \
    -type d -path "*/co.electriccoin.zcash/checkpoint/$network" 2>/dev/null | head -1)"
if [[ -z "$merged_dir" ]]; then
    echo "error: no merged checkpoint assets found. Build the app once first, e.g." >&2
    echo "  ./gradlew :app:assembleZcashmainnetFossDebug" >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Hand-written because the SDK ships no .proto. Field numbers were read off the
# compiled lightwallet-client classes; `ironwoodTree` is this fork's third
# shielded pool and upstream tooling that predates it will silently omit the
# field, which desyncs the ironwood commitment tree for anyone starting here.
cat > "$work/service.proto" <<'PROTO'
syntax = "proto3";
package cash.z.wallet.sdk.rpc;

message BlockID  { uint64 height = 1; bytes hash = 2; }
message Empty    {}
message LightdInfo {
  string version = 1; string vendor = 2; bool taddrSupport = 3; string chainName = 4;
  uint64 saplingActivationHeight = 5; string consensusBranchId = 6; uint64 blockHeight = 7;
}
message TreeState {
  string network = 1; uint64 height = 2; string hash = 3; uint32 time = 4;
  string saplingTree = 5; string orchardTree = 6; string ironwoodTree = 7;
}
service CompactTxStreamer {
  rpc GetLightdInfo(Empty) returns (LightdInfo);
  rpc GetTreeState(BlockID) returns (TreeState);
}
PROTO

# grpcurl refuses an absolute -proto path without -import-path, so both are given.
grpc() {
    grpcurl -import-path "$work" -proto service.proto "$@"
}

# Writing the formatter to a file rather than piping into `python3 -`: with a
# heredoc, the heredoc becomes stdin and the piped response is lost.
cat > "$work/format.py" <<'PY'
import json, sys

expected_height = int(sys.argv[1])
expected_network = sys.argv[2]
raw = json.load(sys.stdin)

height = int(raw.get("height", 0))
network = raw.get("network", "")
hash_ = raw.get("hash", "")
time = int(raw.get("time", 0))
trees = {k: raw.get(k, "") for k in ("saplingTree", "orchardTree", "ironwoodTree")}

def die(why):
    sys.stderr.write("rejected height %d: %s\n" % (expected_height, why))
    sys.exit(1)

# Every one of these has been a real way to ship a file the SDK would happily
# load and then mis-scan against, which is far worse than shipping nothing.
if height != expected_height:
    die("server echoed height %d" % height)
if network != expected_network:
    die("server says network %r, wanted %r" % (network, expected_network))
if len(hash_) != 64 or any(c not in "0123456789abcdefABCDEF" for c in hash_):
    die("hash is not 64 hex characters: %r" % hash_)
if time <= 0:
    die("no block time")
for name, value in trees.items():
    if not value:
        die("%s is empty" % name)

# Key order, `height` as a string, `time` as a number, two-space indent and a
# trailing newline: the SDK's own files, byte for byte.
out = {
    "network": network,
    "height": str(height),
    "hash": hash_,
    "time": time,
    "saplingTree": trees["saplingTree"],
    "orchardTree": trees["orchardTree"],
    "ironwoodTree": trees["ironwoodTree"],
}
sys.stdout.write(json.dumps(out, indent=2) + "\n")
PY

# Fetches one checkpoint from one server into $2, or fails.
fetch() {
    local height="$1" dest="$2" host="$3"
    grpc -d "{\"height\": $height}" "$host" cash.z.wallet.sdk.rpc.CompactTxStreamer/GetTreeState \
        | python3 "$work/format.py" "$height" "$expected_network" > "$dest"
}

# --- Validate the method before trusting a single byte of its output ---------
#
# Regenerate a checkpoint the SDK already ships and diff it. If this does not
# reproduce the shipped file exactly then something about the request, the field
# numbers or the formatting is wrong, and every file this run would write is
# wrong the same way.
known="$(ls "$merged_dir" | sed 's/\.json$//' | sort -n | tail -1)"
echo "Validating against the newest bundled checkpoint ($known) ..."
fetch "$known" "$work/known.json" "$endpoint"
if ! diff -q "$merged_dir/$known.json" "$work/known.json" >/dev/null; then
    echo "error: regenerating $known.json did not reproduce the bundled file." >&2
    echo "       The generation method is wrong; nothing was written." >&2
    diff "$merged_dir/$known.json" "$work/known.json" >&2 || true
    exit 1
fi
echo "  reproduced $known.json byte for byte"

tip="$(grpc -d '{}' "$endpoint" cash.z.wallet.sdk.rpc.CompactTxStreamer/GetLightdInfo \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["blockHeight"])')"
ceiling=$((tip - TIP_MARGIN))
echo "Chain tip $tip, generating up to $ceiling in steps of $STRIDE"

mkdir -p "$out_dir"
written=0
height=$((known + STRIDE))
while [[ $height -le $ceiling ]]; do
    echo "  $height ..."
    fetch "$height" "$work/$height.json" "$endpoint"

    # Cross-check against a second, independent server. One server handing back
    # a tree that is subtly wrong is not detectable from its own answer.
    if [[ -n "$cross_endpoint" ]]; then
        if fetch "$height" "$work/$height.cross.json" "$cross_endpoint" 2>/dev/null; then
            if ! diff -q "$work/$height.json" "$work/$height.cross.json" >/dev/null; then
                echo "error: $endpoint and $cross_endpoint disagree at $height." >&2
                exit 1
            fi
        else
            echo "    warning: $cross_endpoint did not answer; wrote unverified" >&2
        fi
    fi

    mv "$work/$height.json" "$out_dir/$height.json"
    written=$((written + 1))
    height=$((height + STRIDE))
done

if [[ $written -eq 0 ]]; then
    echo "Already current: nothing above $known and below $ceiling."
else
    echo "Wrote $written checkpoint(s) to ${out_dir#"$repo_root"/}"
fi
