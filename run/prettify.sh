#!/bin/sh
# Builds and runs the source checkout of the Prettify CLI.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEVTOOLS_DIR=$(dirname -- "$SCRIPT_DIR")
TOOL_DIR="$DEVTOOLS_DIR/prettify"
CLASSPATH_FILE="$TOOL_DIR/target-cli/prettify-classpath.txt"

mvn \
  -q \
  -f "$DEVTOOLS_DIR/pom.xml" \
  -pl :prettify \
  -DskipTests \
  -Dmaven.test.skip=true \
  -Dprettify.build.directory=target-cli \
  -Dmdep.outputFile="$CLASSPATH_FILE" \
  -Dmdep.includeScope=runtime \
  compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath

CLI_CP="$TOOL_DIR/target-cli/classes"
if [ -s "$CLASSPATH_FILE" ]; then
  CLI_CP="$CLI_CP:$(cat "$CLASSPATH_FILE")"
fi

java -cp "$CLI_CP" de.westarps.devtools.prettify.PrettifyCli "$@"
