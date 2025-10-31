import subprocess, os, re
import time
import requests

# How many retries per report
MAX_RETRIES = 10
RETRY_DELAY = 0.25  # seconds

TYPES_URL = os.getenv(
    "METAIS_TYPES_URL",
    "https://metais-test.slovensko.sk/api/types-repo/citypes/list"
)

INCLUDE_TYPES = set(
    os.getenv("METAIS_INCLUDE_TYPES", "application").split(",")
)  # e.g. "application,domain"
INCLUDE_REGEX = os.getenv("METAIS_INCLUDE_REGEX", "")  # e.g. r"^(Agenda|AS|ISVS|KS|Projekt|Program|ZS|InfraSluzba|Integracia|Kanal|KRIS)$"
EXCLUDE_REGEX = os.getenv("METAIS_EXCLUDE_REGEX", r"^(CMDB_|CMDB|CI_|CI$|CMDB_REQUEST|CMDB_REQUEST_TRACKER|CMDB_HISTORY_)")

FORCE_INCLUDE = set(filter(None, [s.strip() for s in os.getenv("METAIS_FORCE_INCLUDE", "").split(",")]))

RAW_CMD = os.getenv("METAIS_RAW_CMD", "run/raw.sh {name}")
MAX_RETRIES = int(os.getenv("METAIS_MAX_RETRIES", "10"))
RETRY_DELAY = float(os.getenv("METAIS_RETRY_DELAY", "0.25"))
FETCH_TIMEOUT = float(os.getenv("METAIS_FETCH_TIMEOUT", "20"))

FALLBACK_REPORTS = ["Agenda", "AS", "InfraSluzba", "Integracia", "ISVS", "Kanal", "KRIS", "KS", "Projekt", "Program", "ZS"]

def fetch_citypes():
    headers = {"Accept": "application/json"}

    print(f"[INFO] Fetching types from {TYPES_URL}")
    resp = requests.get(TYPES_URL, headers=headers, timeout=FETCH_TIMEOUT)
    resp.raise_for_status()
    data = resp.json()

    if not isinstance(data, dict) or "results" not in data:
        raise ValueError("Unexpected response shape (no 'results' key).")

    return data["results"]

def build_report_list(results):
    include_re = re.compile(INCLUDE_REGEX) if INCLUDE_REGEX else None
    exclude_re = re.compile(EXCLUDE_REGEX) if EXCLUDE_REGEX else None

    reports = []
    for item in results:
        # Defensive parsing
        tech = item.get("technicalName") or item.get("name")
        if not tech:
            continue

        # Filter by type and validity
        typ = (item.get("type") or "").strip().lower()
        valid = bool(item.get("valid", True))

        if INCLUDE_TYPES and typ not in INCLUDE_TYPES:
            continue
        if not valid:
            continue

        # Regex filters
        if include_re and not include_re.search(tech):
            # Not in include regex; might still be force-included later
            pass
        elif exclude_re and exclude_re.search(tech):
            continue
        # If included by the include_re, add now
        if (not include_re) or include_re.search(tech):
            reports.append(tech)

    # Add force-includes even if filtered out above
    for name in FORCE_INCLUDE:
        if name and name not in reports:
            reports.append(name)

    # Dedup (preserve order) & sort for stability
    seen = set()
    deduped = [x for x in reports if not (x in seen or seen.add(x))]

    if not deduped and not FORCE_INCLUDE:
        # If filtering is too strict, try a sane broadened set:
        print("[WARN] Filtered list is empty; falling back to your previous hand-picked set.")
        return FALLBACK_REPORTS

    return deduped

def run_with_retries(report_name, idx, total):
    print(f"\n=== Downloading raw report {report_name} ({idx}/{total}) ===")
    attempt = 1
    while attempt <= MAX_RETRIES:
        try:
            cmd = RAW_CMD.format(name=report_name)
            process = subprocess.run(
                cmd,
                shell=True,
                text=True,
                check=True,
                capture_output=True,
            )

            # Reprint command output, but add (done idx/total) after “Wrote: …”
            for line in process.stdout.splitlines():
                if line.startswith("Wrote:"):
                    print(f"{line} (done {idx}/{total})")
                else:
                    print(line)

            print(f"[OK] {report_name} downloaded successfully")
            return True

        except subprocess.CalledProcessError as e:
            print(f"[WARN] {report_name}: attempt {attempt}/{MAX_RETRIES} failed. Retrying in {RETRY_DELAY}s...")
            time.sleep(RETRY_DELAY)
            attempt += 1

    print(f"[ERROR] {report_name}: failed after {MAX_RETRIES} attempts. Moving on...")
    return False

def main():
    try:
        results = fetch_citypes()
        reports = build_report_list(results)
        print(f"[INFO] Will process {len(reports)} types: {', '.join(reports)}")
    except Exception as e:
        print(f"[ERROR] Could not fetch types ({e}). Using fallback list.", file=sys.stderr)
        reports = FALLBACK_REPORTS

    total = len(reports)
    failures = 0
    for i, report in enumerate(reports, start=1):
        ok = run_with_retries(report, i, total)
        if not ok:
            failures += 1

    print(f"\n[INFO] Completed: {total - failures} ok / {failures} failed.")


if __name__ == "__main__":
    main()