#!/usr/bin/env bash
set -euo pipefail

# Load helpers first
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "${SCRIPT_DIR}/lib.sh"

# Defaults
OUTPUT_BASE="output"                     # base name (no ext)
SCRIPT_PATH="groovy/script.groovy"       # relative to project root
PARAMS_PATH="params/params.json"
OUTDIR="$(dirname "$SCRIPT_DIR")/output" # default ../output
RUN_CONVERT=1
APIURI="$APIURI_DEFAULT"

PAGE_SET=0; PERPAGE_SET=0
PAGE_VAL=""; PERPAGE_VAL=""

# --- parse args ---
while (( "$#" )); do
  case "$1" in
    -o|--output)   OUTPUT_BASE="${2:-}"; shift 2 ;;
    -d|--outdir)   OUTDIR="${2:-}"; shift 2 ;;
    -p|--page)     PAGE_VAL="${2:-}"; PAGE_SET=1; shift 2 ;;
    -P|--per-page) PERPAGE_VAL="${2:-}"; PERPAGE_SET=1; shift 2 ;;
    -s|--script)
      SCRIPT_PATH="$(resolve_groovy_path "${2:-}")"
      shift 2 ;;
    -A|--api)      APIURI="${2:-}"; shift 2 ;;
    --params)      PARAMS_PATH="${2:-}"; shift 2 ;;
    --no-csv)      RUN_CONVERT=0; shift ;;
    -h|-H|--help)  print_help; exit 0 ;;
    *) echo "Unknown option: $1"; echo "Try --help"; exit 1 ;;
  esac
done

# --- normalize paths & names ---
: "${TOKEN:?TOKEN env var is required}"

SCRIPT_PATH="$(expand_tilde "$SCRIPT_PATH")"
PARAMS_PATH="$(expand_tilde "$PARAMS_PATH")"
OUTPUT_BASE="$(expand_tilde "$OUTPUT_BASE")"
OUTDIR="$(expand_tilde "$OUTDIR")"

SCRIPT_PATH="$(ensure_groovy_ext "$SCRIPT_PATH")"
[[ -f "$SCRIPT_PATH" ]] || { echo "Script not found: $SCRIPT_PATH"; exit 1; }
[[ -f "$PARAMS_PATH" ]] || { echo "Params not found: $PARAMS_PATH"; exit 1; }

mkdir -p "$OUTDIR"   # ensure output dir exists

# --- derive default output name from script if not explicitly set ---
if [[ -z "${OUTPUT_BASE:-}" || "$OUTPUT_BASE" == "output" ]]; then
  # Extract filename from script path (strip dir and .groovy)
  script_basename="$(basename "$SCRIPT_PATH")"
  script_name="${script_basename%.groovy}"
  OUTPUT_BASE="$script_name"
fi

compute_outputs "$OUTPUT_BASE"
# Prepend OUTDIR to all computed files
OUT_JSON="${OUTDIR}/$(basename "$OUT_JSON")"
OUT_CSV="${OUTDIR}/$(basename "$OUT_CSV")"

normalize_paging "$PAGE_SET" "$PAGE_VAL" "$PERPAGE_SET" "$PERPAGE_VAL"

# --- export contract for core.sh ---
export APIURI SCRIPT_PATH PARAMS_PATH OUT_JSON PAGE_JSON PERPAGE_JSON TOKEN

# --- run core ---
"${SCRIPT_DIR}/core.sh"
echo "Wrote: $OUT_JSON"

# --- optional convert ---
if (( RUN_CONVERT )); then
  if [[ -x "${SCRIPT_DIR}/convert.sh" ]]; then
    "${SCRIPT_DIR}/convert.sh" "$OUT_JSON" "$OUT_CSV"
    echo "Wrote: $OUT_CSV"
  elif [[ -x "./convert.sh" ]]; then
    ./convert.sh "$OUT_JSON" "$OUT_CSV"
    echo "Wrote: $OUT_CSV"
  else
    echo "NOTE: ${SCRIPT_DIR}/convert.sh not found; skipping CSV." >&2
  fi
fi