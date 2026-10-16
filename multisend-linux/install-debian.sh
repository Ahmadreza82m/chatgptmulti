#!/bin/bash
set -e
cd "$(dirname "$0")"

apt update
apt install -y python3 python3-pip python3-venv git
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m playwright install chromium
mkdir -p sessions outputs
chmod +x start-debian.sh

echo
echo "Installed. Run ./start-debian.sh"
