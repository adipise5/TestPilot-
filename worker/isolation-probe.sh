#!/bin/sh
set -eu

test "$(id -u)" != "0"
test ! -w /etc
test ! -r /host-probe-secret

if command -v getent >/dev/null 2>&1; then
  ! getent ahosts example.com >/dev/null 2>&1
fi

echo "worker isolation checks passed"
