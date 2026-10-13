#!/bin/sh
# Run busy-agent.py at login as a systemd user service.
#   ./install.sh https://hearth.example.com [--ignore "Dummy video device"]   install / update
#   ./install.sh --uninstall
set -e
UNIT=hearth-busy-agent
DIR="$HOME/.local/share/hearth"
UNIT_FILE="$HOME/.config/systemd/user/$UNIT.service"
if [ "$1" = "--uninstall" ]; then
  systemctl --user disable --now "$UNIT" 2>/dev/null || true
  rm -f "$UNIT_FILE" "$DIR/busy-agent.py"; systemctl --user daemon-reload; echo "removed"; exit 0
fi
[ -n "$1" ] || { echo "usage: ./install.sh <hearth url> [--ignore <device>]..." >&2; exit 2; }
command -v pw-dump >/dev/null || echo "warning: pw-dump not found (package pipewire-utils or pipewire-bin); only /dev/video* will be watched" >&2
cd "$(dirname "$0")"
mkdir -p "$DIR" "$(dirname "$UNIT_FILE")"
cp busy-agent.py "$DIR/busy-agent.py"
# systemd splits ExecStart on spaces and honours double quotes, so a device
# name with a space in it has to be quoted here or it arrives as three words.
ARGS=""; for a in "$@"; do ARGS="$ARGS \"$a\""; done
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=Hearth busy agent
After=default.target

[Service]
ExecStart=/usr/bin/env python3 "$DIR/busy-agent.py"$ARGS
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now "$UNIT"
echo "installed: $UNIT → $1 (log: journalctl --user -u $UNIT -f)"
