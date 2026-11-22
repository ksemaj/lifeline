@echo off
rem Restart loop for FateLink's world reset (Windows).
rem Put this next to your server jar, set JAR below, then double-click it.
set JAR=server.jar
set MEMORY=4G
:loop
java -Xms%MEMORY% -Xmx%MEMORY% -jar %JAR% --nogui
echo Server stopped. Restarting in 5 seconds (close this window to quit)...
timeout /t 5
goto loop
