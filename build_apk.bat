@echo off
echo Building APK through Windows...
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
gradlew.bat :app:assembleDebug
echo Build complete!
pause