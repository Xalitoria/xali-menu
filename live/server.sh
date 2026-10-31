#!/usr/bin/env bash
# The server of the live tests (set up by setup.sh).
#
#   server.sh start      start it and wait until it is ready; its console is the named pipe $XM_DIR/server/in
#   server.sh stop       stop it
#   server.sh cmd <...>  run a console command
#   server.sh log        the log, without colors
set -euo pipefail
dir=${XM_DIR:-/tmp/xm-live}/server
[ -x "${JAVA_HOME:-}/bin/java" ] || export JAVA_HOME=/opt/openjdk-bin-25
cd "$dir"
case "${1:-}" in
  start)
    [ -p in ] || mkfifo in
    # Something has to keep the pipe open, or the server reads the end of the file when a command has been written.
    [ -f .holder ] || { (sleep 7200 > in) >/dev/null 2>&1 & echo $! > .holder; }
    : > server.log
    nohup "$JAVA_HOME/bin/java" -Xmx1500M -jar folia-*.jar --nogui < in > server.log 2>&1 &
    for _ in $(seq 1 180); do grep -aq 'Done (' server.log && break; sleep 1; done
    sed 's/\x1b\[[0-9;]*m//g' server.log | grep -aE "Done \(|XaliMenu\] (Item|Loaded|Menu)|ERROR|Exception" | head -20 ;;
  stop)
    echo stop > in
    sleep 6
    [ -f .holder ] && { kill "$(cat .holder)" 2>/dev/null || true; rm -f .holder; } ;;
  cmd)
    shift
    echo "$*" > in ;;
  log)
    sed 's/\x1b\[[0-9;]*m//g' server.log ;;
  *)
    echo "usage: server.sh start|stop|cmd <command>|log" >&2
    exit 1 ;;
esac
