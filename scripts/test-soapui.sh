#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
soapui_dir="${SOAPUI_HOME:-/Applications/SoapUI-5.10.0.app/Contents/Resources/app}"
[[ -f "$soapui_dir/bin/testrunner.sh" ]] || { echo "Set SOAPUI_HOME to your SoapUI installation directory" >&2; exit 1; }
mkdir -p target/soapui-reports
bash "$soapui_dir/bin/testrunner.sh" -r -j -a -ftarget/soapui-reports \
  docs/soapui/country-info-soapui-project.xml
