# Compile and test. No build tool on purpose: the library has zero dependencies,
# so a JDK is the whole toolchain.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (Test-Path out) { Remove-Item out -Recurse -Force }
New-Item -ItemType Directory out | Out-Null

$sources = Get-ChildItem -Path src, test -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& javac -Xlint:all -d out @sources
if ($LASTEXITCODE -ne 0) { throw "compilation failed" }

& java -cp out ledger.Tests
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
