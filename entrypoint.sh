#!/bin/sh
set -e

# 后台启动 web console
/erii/erii-cli web start $WEB_OPTS --daemon

# 前台启动 erii server，JAVA_OPTS 直接透传给 Java 进程（作为容器 PID 1）
exec /erii/erii-cli server start $JAVA_OPTS --foreground
