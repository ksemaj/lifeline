#!/usr/bin/env bash
# Restart loop for FateLink's world reset (macOS / Linux).
# Put this next to your server jar, set JAR below, then: chmod +x start.sh && ./start.sh
# When the server stops (crash, /stop or a FateLink world reset) it starts again after 5 s.
# Press Ctrl+C during the countdown to stop for good.
JAR="server.jar"      # e.g. paper-26.3.jar or folia-26.3.jar
MEMORY="4G"

while true; do
  java -Xms"$MEMORY" -Xmx"$MEMORY" -jar "$JAR" --nogui
  echo "Server stopped. Restarting in 5 seconds (Ctrl+C to quit)..."
  sleep 5
done
