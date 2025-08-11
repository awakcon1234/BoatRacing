@ECHO OFF
setlocal

set WRAPPER_JAR="%~dp0\.mvn\wrapper\maven-wrapper-3.3.2.jar"
set WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar

if not exist %WRAPPER_JAR% (
  echo Downloading Maven Wrapper...
  powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; iwr -useb %WRAPPER_URL% -OutFile %WRAPPER_JAR%" || (
    echo Failed to download Maven Wrapper.& exit /b 1
  )
)

set MAVEN_PROJECTBASEDIR=%~dp0
set JAVA_EXE=java

"%JAVA_EXE%" -classpath %WRAPPER_JAR% -Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR% org.apache.maven.wrapper.MavenWrapperMain %*

endlocal
