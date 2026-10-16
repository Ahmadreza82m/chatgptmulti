#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

pkg update -y
pkg install -y python git
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m playwright install chromium

mkdir -p sessions outputs
chmod +x start.sh

echo
echo "MultiSend Linux backend installed."
echo "Run: ./start.sh"
