#!/usr/bin/env bash
set -euo pipefail
TYPE="${1:?usage: $0 <TYPE> [--no-csv] }"; shift || true
export SCRIPT_CONTENT="$(sed "s|__TYPE__|${TYPE}|g" groovy/templates/extract_raw_template.groovy)"
run/run.sh -o "${TYPE}_raw" "--no-csv"