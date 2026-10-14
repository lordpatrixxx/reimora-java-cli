@echo off
setlocal
cd /d "%~dp0"
if not exist build mkdir build
javac --release 17 -encoding UTF-8 -sourcepath src -d build src/reimora/Main.java tests/reimora/SelfTest.java
if errorlevel 1 exit /b 1
java -cp build reimora.SelfTest
