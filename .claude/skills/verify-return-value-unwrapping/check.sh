#!/usr/bin/env bash
set -euo pipefail

checkout=$(git rev-parse --show-toplevel)
log=$(mktemp "${TMPDIR:-/tmp}/verify-return-value-unwrapping.XXXXXX")

echo "checkout $checkout at $(git -C "$checkout" rev-parse --short HEAD)$(git -C "$checkout" diff --quiet HEAD -- src || echo ' plus uncommitted src changes')"
java -version 2>&1 | head -1

if (cd "$checkout" && mvn -B -ntp clean test -Dtest=ReturnValueUnwrappingTest "-Dunwrapping.args=$*") > "$log" 2>&1; then
  sed -n '/Running .*ReturnValueUnwrappingTest/,/Tests run:/p' "$log" | sed -e '1d' -e '$d'
  rm "$log"
  exit 0
fi

sed -n '/AssertionError: /,/^\tat /p' "$log" | sed -e '1s/.*AssertionError: //' -e '$d'
echo "Maven log: $log"
grep -q '^FAIL:' "$log" && exit 1
grep -q 'MODEL ERROR:' "$log" && exit 2
grep -q '^STALE:' "$log" && exit 3
tail -40 "$log"
echo "MODEL ERROR: the checkout didn't build"
exit 2
