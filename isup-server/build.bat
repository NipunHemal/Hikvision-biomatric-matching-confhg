@echo off
REM Compile the ISUP server. Uses the SDK's bundled (old) JNA jars in lib/.
if not exist out mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt
if %errorlevel%==0 (echo BUILD OK) else (echo BUILD FAILED)
del sources.txt
