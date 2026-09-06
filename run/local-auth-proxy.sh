#!/bin/sh
# Start the standalone credential broker from an ordinary interactive terminal.
set -eu
HELP=false
if [ "$#" -eq 1 ] && { [ "$1" = "--help" ] || [ "$1" = "-h" ]; }; then
  HELP=true
fi
if [ "$HELP" = false ]; then
  if [ "$#" -ne 2 ] || [ "$1" != "imap" ] || [ ! -t 0 ] || [ ! -t 1 ]; then
    echo "Usage (interactive terminal): $0 imap /absolute/path/to/mail-proxy.properties" >&2
    exit 2
  fi
fi
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEVTOOLS_DIR=$(dirname -- "$SCRIPT_DIR")
JAR="$DEVTOOLS_DIR/local-auth-proxy/target/local-auth-proxy-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "Build first in devtools: mvn -pl local-auth-proxy -am package" >&2
  exit 2
fi
# Reduce accidental memory dumps; this does not isolate the operating-system user.
ulimit -c 0
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi
exec "$JAVA" -XX:-HeapDumpOnOutOfMemoryError -XX:-CreateCoredumpOnCrash -XX:+DisableAttachMechanism -jar "$JAR" "$@"
