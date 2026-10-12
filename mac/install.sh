#!/bin/sh
# Build busy-agent and run it at login as a LaunchAgent.
#   ./install.sh https://hearth.example.com        install / update
#   ./install.sh --uninstall
set -e
LABEL=dev.hearth.busy-agent
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
BIN="$HOME/Library/Application Support/hearth/busy-agent"
if [ "$1" = "--uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$BIN"; echo "removed"; exit 0
fi
URL="${1:?usage: ./install.sh <hearth url>}"
cd "$(dirname "$0")"
mkdir -p "$(dirname "$BIN")"
swiftc -O busy-agent.swift -o "$BIN"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$BIN</string><string>$URL</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/hearth-busy-agent.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/hearth-busy-agent.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: $LABEL → $URL (log: ~/Library/Logs/hearth-busy-agent.log)"
