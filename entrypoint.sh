#!/bin/sh
set -e

# Start web console in background
/erii/erii-cli web start $WEB_OPTS --daemon

# Start erii server in foreground, JAVA_OPTS passed through to Java process (as container PID 1)
exec /erii/erii-cli server start $JAVA_OPTS --foreground
