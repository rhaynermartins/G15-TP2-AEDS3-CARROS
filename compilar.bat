@echo off
if not exist out mkdir out
javac -encoding UTF-8 -d out -sourcepath src src\*.java
if %errorlevel% neq 0 exit /b %errorlevel%
echo Compilacao concluida.
