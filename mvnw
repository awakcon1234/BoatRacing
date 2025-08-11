#!/usr/bin/env sh

WRAPPER_JAR="$(dirname "$0")/.mvn/wrapper/maven-wrapper-3.3.2.jar"
WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper..."
  curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR" || wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR" || {
    echo "Failed to download Maven Wrapper."; exit 1;
  }
fi

JAVA_EXE=java

"$JAVA_EXE" -classpath "$WRAPPER_JAR" -Dmaven.multiModuleProjectDirectory="$(pwd)" org.apache.maven.wrapper.MavenWrapperMain "$@"
