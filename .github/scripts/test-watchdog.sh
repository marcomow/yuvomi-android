#!/usr/bin/env bash
# Runs a command and, if it is still running after $WATCHDOG_AFTER seconds, prints the thread
# stacks of every Gradle test worker JVM (then again every $WATCHDOG_EVERY seconds), so a test
# that hangs names itself in the log and in app/build/reports/hang/ instead of only timing out.
#
#   .github/scripts/test-watchdog.sh ./gradlew --no-daemon testDebugUnitTest
set -uo pipefail

after="${WATCHDOG_AFTER:-720}"
every="${WATCHDOG_EVERY:-300}"
out="app/build/reports/hang"

dump() {
  mkdir -p "$out"
  local stamp pid
  stamp="$(date -u +%H%M%S)"
  for pid in $(jps -l 2>/dev/null | awk '/GradleWorkerMain|Gradle Test Executor/ { print $1 }'); do
    echo "::group::Thread stacks of test worker $pid at $stamp UTC"
    jcmd "$pid" Thread.print 2>&1 | tee "$out/worker-$pid-$stamp.txt"
    echo "::endgroup::"
    echo "::warning::Tests still running after ${after}s; stacks of worker $pid are in the log and the test-reports artifact."
  done
}

"$@" &
cmd=$!
(
  sleep "$after"
  while kill -0 "$cmd" 2>/dev/null; do
    dump
    sleep "$every"
  done
) &
watch=$!
wait "$cmd"
status=$?
kill "$watch" 2>/dev/null
exit "$status"
