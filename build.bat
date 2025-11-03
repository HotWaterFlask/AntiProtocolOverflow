@echo off

REM Maven build script for AntiProtocolOverflow

REM Build the project
mvn clean package

REM Create output directory if it doesn't exist
mkdir output 2>nul

REM Copy the built jar file to output directory
copy target\*.jar output\ 2>nul

echo Build completed.
if exist output\*.jar (
    echo Build artifacts copied to output directory.
) else (
    echo Warning: No build artifacts found. Please check build process.
)

echo Press any key to exit...
pause > nul