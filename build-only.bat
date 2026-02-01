@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
echo Building APK...
call gradlew.bat clean assembleDebug
echo Build complete. Exit code: %errorlevel%
