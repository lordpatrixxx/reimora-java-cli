@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo JDK 17 or later is required. Add its bin directory to PATH.
  exit /b 1
)
if not exist build mkdir build
javac --release 17 -encoding UTF-8 -sourcepath src -d build src/reimora/Main.java
if errorlevel 1 exit /b 1
java -cp build reimora.Main %*
