#!/usr/bin/env python3
import subprocess
import time
import sys
import os, re, requests

CITYPES_URL = os.getenv("METAIS_TYPES_URL",
    "https://metais-test.slovensko.sk/api/types-repo/citypes/list")
REL_TYPES_URL = os.getenv("METAIS_REL_URL",
    "https://metais-test.slovensko.sk/api/types-repo/relationshiptypes/list")

# Include only application-level stuff by default (skip 'system' types).
INCLUDE_NODE_TYPES = set(os.getenv("METAIS_INCLUDE_NODE_TYPES", "application").split(","))
INCLUDE_REL_TYPES  = set(os.getenv("METAIS_INCLUDE_REL_TYPES", "application").split(","))

# Skip invalid=false relationship types
ONLY_VALID = os.getenv("METAIS_ONLY_VALID", "1") not in ("0", "false", "False", "no", "n")

# Known irregular technicalName → (central, verb, outer)
# You can extend this list as you find more weird names.
KNOWN_OVERRIDES = {
    "Projekt_je_asociovany_s_projektom": ("Projekt", "asociuje", "Projekt"),
}

# A small helper to allow/deny relations by regex on technicalName (optional)
INCLUDE_REGEX = os.getenv("METAIS_REL_INCLUDE_REGEX", "")  # e.g. r"^(AS|KS|Projekt)_"
EXCLUDE_REGEX = os.getenv("METAIS_REL_EXCLUDE_REGEX", r"^(CMDB_|LATEST_REQUEST|PREVIOUS_REQUEST)$")

# Runner + retries
RAW_CMD = os.getenv("METAIS_REL_CMD", "run/relation.sh {central} {outer} {verb} {override} --no-csv")
MAX_RETRIES = int(os.getenv("METAIS_MAX_RETRIES", "10"))
RETRY_DELAY = float(os.getenv("METAIS_RETRY_DELAY", "0.25"))
TIMEOUT = float(os.getenv("METAIS_FETCH_TIMEOUT", "25"))

# -----------------------------------------------------------------------


def fetch_json(url: str):
    r = requests.get(url, timeout=TIMEOUT, headers={"Accept": "application/json"})
    r.raise_for_status()
    data = r.json()
    return data.get("results", [])


def build_node_set(citypes):
    nodes = set()
    for it in citypes:
        typ = (it.get("type") or "").lower()
        if INCLUDE_NODE_TYPES and typ not in INCLUDE_NODE_TYPES:
            continue
        if ONLY_VALID and not it.get("valid", True):
            continue
        name = it.get("technicalName") or it.get("name")
        if name:
            nodes.add(name)
    return nodes


def tokenize(tn: str):
    # Split on underscores; keep case as-is for node matches
    parts = tn.split("_")
    # Strip empty parts, normalize accidental spaces
    return [p.strip() for p in parts if p.strip()]


def infer_triplet(tn: str, node_set: set[str]):
    """
    Try to infer (central, verb, outer) from technicalName like 'Projekt_realizuje_AS'.
    If irregular, consult KNOWN_OVERRIDES. If still unknown, return None.
    """
    if tn in KNOWN_OVERRIDES:
        c, v, o = KNOWN_OVERRIDES[tn]
        return c, v, o, True  # override flag True

    parts = tokenize(tn)
    if len(parts) < 3:
        return None

    # Heuristic 1: exact head/tail node names
    head, tail = parts[0], parts[-1]
    if head in node_set and tail in node_set:
        verb = "_".join(parts[1:-1])
        return head, verb, tail, False

    # Heuristic 2: case-insensitive tail with common Slovak endings stripped (e.g., 'projektom' → 'Projekt')
    # Only attempt if head is a known node.
    if head in node_set:
        raw_tail = parts[-1]
        # try to desuffix (very light heuristic)
        endings = ("om", "em", "am", "om", "u", "a", "y", "i", "e", "ou", "ov", "om")
        base = raw_tail
        for suf in endings:
            if base.lower().endswith(suf):
                base = base[:-len(suf)]
                break
        # Try capitalized base
        cap = base[:1].upper() + base[1:]
        if cap in node_set:
            verb = "_".join(parts[1:-1])
            # irregular name → use override (the exact technicalName)
            return head, verb, cap, True

    return None


def build_rel_specs(reltypes, node_set):
    include_re = re.compile(INCLUDE_REGEX) if INCLUDE_REGEX else None
    exclude_re = re.compile(EXCLUDE_REGEX) if EXCLUDE_REGEX else None

    specs = []  # list of dicts: {central, verb, outer, override (or "") , tech}
    for it in reltypes:
        typ = (it.get("type") or "").lower()
        if INCLUDE_REL_TYPES and typ not in INCLUDE_REL_TYPES:
            continue
        if ONLY_VALID and not it.get("valid", True):
            continue

        tech = it.get("technicalName")
        if not tech:
            continue

        if include_re and not include_re.search(tech):
            continue
        if exclude_re and exclude_re.search(tech):
            continue

        inf = infer_triplet(tech, node_set)
        if not inf:
            continue

        central, verb, outer, needs_override = inf

        # 🔁 Flip central <-> outer (e.g., KS_je_gestor_PO -> central=PO, outer=KS)
        central, outer = outer, central

        # If the canonical pattern equals the tech name, no override needed
        canonical = f"{central}_{verb}_{outer}"
        override = tech if (needs_override or canonical != tech) else ""

        specs.append({
            "central": central,
            "verb": verb,
            "outer": outer,
            "override": override,
            "tech": tech,
        })

    specs.sort(key=lambda s: (s["central"], s["verb"], s["outer"], s["tech"]))
    return specs


def run_one(spec, idx, total):
    central, verb, outer, override = spec["central"], spec["verb"], spec["outer"], spec["override"]
    label = f"{central}_{verb}_{outer}" + (f" [{override}]" if override else "")

    # Build command; remove the {override} arg if empty so we don't pass a dangling word
    if override:
        cmd = RAW_CMD.format(central=central, outer=outer, verb=verb, override=override)
    else:
        cmd = RAW_CMD.replace("{override} ", "").format(central=central, outer=outer, verb=verb, override="")

    print(f"\n=== Generating relation {central} <--({verb})-- {outer}  ({idx}/{total}) ===")
    attempt = 1
    while attempt <= MAX_RETRIES:
        try:
            proc = subprocess.run(
                cmd,
                shell=True,
                text=True,
                check=True,
                capture_output=True,
            )
            # Echo stdout, and append (done x/y) after any "Wrote:" line.
            for line in proc.stdout.splitlines():
                if line.startswith("Wrote:"):
                    print(f"{line} (done {idx}/{total})")
                else:
                    print(line)
            print(f"[OK] {label}")
            return True
        except subprocess.CalledProcessError as e:
            print(f"[WARN] {label}: attempt {attempt}/{MAX_RETRIES} failed. Retrying in {RETRY_DELAY}s...")
            if e.stdout:
                for line in e.stdout.splitlines():
                    print(line)
            if e.stderr:
                for line in e.stderr.splitlines():
                    print(line)
            time.sleep(RETRY_DELAY)
            attempt += 1

    print(f"[ERROR] Giving up on {label}")
    return False


def group_by_central(specs):
    by_central = {}
    for s in specs:
        by_central.setdefault(s["central"], []).append(s)
    return by_central


def main():
    # Fetch node types and relationship types
    try:
        citypes = fetch_json(CITYPES_URL)
        node_set = build_node_set(citypes)
    except Exception as e:
        print(f"[ERROR] Failed to fetch or parse node types: {e}", file=sys.stderr)
        sys.exit(1)

    try:
        reltypes = fetch_json(REL_TYPES_URL)
    except Exception as e:
        print(f"[ERROR] Failed to fetch relationship types: {e}", file=sys.stderr)
        sys.exit(1)

    specs = build_rel_specs(reltypes, node_set)
    if not specs:
        print("[ERROR] No usable relationship types after filtering/inference.", file=sys.stderr)
        sys.exit(1)

    by_central = group_by_central(specs)

    # If user specified a central node: limit set
    arg_central = None
    if len(sys.argv) >= 2:
        arg_central = sys.argv[1].strip()
        if arg_central.lower() not in ("", "all", "*"):
            if arg_central not in by_central:
                avail = ", ".join(sorted(by_central.keys()))
                print(f"[ERROR] No inferred relations for central '{arg_central}'. Available: {avail}", file=sys.stderr)
                sys.exit(1)
            # reduce to one central
            specs = by_central[arg_central]

    total = len(specs)
    print(f"[INFO] Will process {total} relations"
          + (f" for central '{arg_central}'" if arg_central and arg_central.lower() not in ("all", "*") else "")
          + ".")

    failures = 0
    for i, spec in enumerate(specs, start=1):
        ok = run_one(spec, i, total)
        if not ok:
            failures += 1

    print(f"\n[INFO] Completed: {total - failures} ok / {failures} failed.")


if __name__ == "__main__":
    main()