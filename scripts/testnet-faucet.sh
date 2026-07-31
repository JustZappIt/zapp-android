#!/usr/bin/env bash
# Open the Zcash testnet faucet in the default browser, prefilled with a
# unified-address argument. The faucet (testnet.zecfaucet.com) is hCaptcha
# protected, so the actual submission has to happen interactively — this
# script just shaves the copy-paste step.
#
# Usage:
#   scripts/testnet-faucet.sh utest1xxxxx...
#   scripts/testnet-faucet.sh                  # tries to read from the
#                                              # connected emulator clipboard
#                                              # (paste the receive address
#                                              #  into the emulator clipboard
#                                              #  first)
#
# After submitting, the emulator wallet will pick up the deposit on its own
# once the next testnet block confirms (~75s). This script does not poll
# for balance — open the app's Wallet tab and watch.

set -euo pipefail

addr="${1:-}"

if [[ -z "$addr" ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "error: no address argument and adb not on PATH" >&2
        exit 2
    fi
    addr=$(adb shell cmd clipboard get-primary 2>/dev/null | tr -d '\r' || true)
    if [[ -z "$addr" ]]; then
        echo "error: clipboard empty. Pass the testnet UA as the first arg," >&2
        echo "       or copy it on the emulator (Receive screen → tap address) first." >&2
        exit 2
    fi
fi

if [[ "$addr" != utest1* ]]; then
    echo "warning: address does not start with 'utest1' — testnet UAs do." >&2
    echo "         Got: $addr" >&2
fi

url="https://testnet.zecfaucet.com/?address=$(printf %s "$addr" | sed 's/ /%20/g')"
echo "opening: $url"

if command -v open >/dev/null 2>&1; then
    open "$url"
elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$url"
else
    echo "$url"
fi

echo
echo "next steps:"
echo "  1. solve the captcha and submit"
echo "  2. wait ~75s for the next testnet block"
echo "  3. open the wallet — balance will appear automatically (already synced to tip)"
