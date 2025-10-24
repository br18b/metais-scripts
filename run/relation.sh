#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   run/relation.sh <CENTRAL> <OUTER> <REL> [RELATION_TYPE] [--no-csv ...]
# Examples:
#   run/relation.sh KS PO je_gestor --no-csv
#     -> type_REL defaults to PO_je_gestor_KS
#
#   run/relation.sh Projekt Projekt asociuje Projekt_je_asociovany_s_projektom --no-csv
#     -> type_REL overridden to "Projekt_je_asociovany_s_projektom"

if [[ $# -lt 3 ]]; then
  echo "usage: $0 <CENTRAL> <OUTER> <REL> [RELATION_TYPE] [extra run args]"
  echo "e.g.   $0 KS PO je_gestor --no-csv"
  echo "e.g.   $0 Projekt Projekt asociuje Projekt_je_asociovany_s_projektom --no-csv"
  exit 1
fi

CENTRAL="$1"; OUTER="$2"; REL="$3"; shift 3 || true

# If the next arg exists and does NOT start with '-', treat it as a full relation type override
RELATION_OVERRIDE=""
if [[ $# -ge 1 && "${1:0:1}" != "-" ]]; then
  RELATION_OVERRIDE="$1"
  shift
fi

# Report base name: central_rel_outer (lowercase)
report_base="$(printf '%s_%s_%s' "$OUTER" "$REL" "$CENTRAL")"

TEMPLATE="groovy/templates/extract_relation_template.groovy"

# Default constructed relation type (OUTER_REL_CENTRAL), unless overridden
TYPE_REL="${RELATION_OVERRIDE:-${OUTER}_${REL}_${CENTRAL}}"

# Prepare inline script content by replacing placeholders
# Template must contain:
#   def type_REL = type("__RELATION__")
#   headers use __CENTRAL__ and __OUTER__
#   qi/type placeholders __CENTRAL__ / __OUTER__
SCRIPT_CONTENT="$(
  sed -e "s|__CENTRAL__|${CENTRAL}|g" \
      -e "s|__OUTER__|${OUTER}|g" \
      -e "s|__RELATION__|${TYPE_REL}|g" \
    "$TEMPLATE"
)"

export SCRIPT_CONTENT
run/run.sh -o "$report_base" --no-csv "$@"