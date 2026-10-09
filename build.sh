#!/usr/bin/env bash
#
# 构建 / 启动入口（Linux / macOS / Git Bash）。
#
# 用法：
#   ./build.sh clean test
#   ./build.sh spring-boot:run
#
# 本项目需要 JDK 17+。Maven 通过 JAVA_HOME 选择 JDK；
# 若 JDK 17 不在当前 JAVA_HOME 下，用 AIKB_JDK 临时指定即可：
#   AIKB_JDK=/usr/lib/jvm/java-17-openjdk ./build.sh clean test
#
set -euo pipefail

if [ -n "${AIKB_JDK:-}" ]; then
  export JAVA_HOME="${AIKB_JDK}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

exec mvn "$@"
