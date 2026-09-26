@echo off
for %%T in (TesteTP1 TesteBPlus TesteHash TesteListas TesteFase2 TesteTP2) do (
    java -Xmx512m -cp out %%T
    if errorlevel 1 exit /b 1
)
echo Todas as suites funcionais passaram.
