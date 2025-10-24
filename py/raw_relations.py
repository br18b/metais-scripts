#!/usr/bin/env python3
import subprocess
import time
import sys

# Hardcoded central → list of (OUTER, RELATION_NAME)
RELATIONS = {
    "KS": [("PO", "je_gestor"), ("Projekt", "realizuje"), ("BP", "realizuje"), ("KS", "sluzi"), ("KS", "prenajima"), ("OE", "ma"),
           ("Kanal", "spristupnuje"), ("AS", "sluzi"), ("ZS", "zoskupuje"), ("PZS", "zoskupuje"), ("Formular", "riesi"), ("PO", "je_poskytovatelom")],
    "AS": [("Projekt", "realizuje"), ("AS", "sluzi"), ("PO", "je_spravca"), ("PO", "je_prevadzkovatel"),
           ("ReferenceRegister", "usedBy"), ("AS", "prenajima"), ("InfraSluzba", "realizuje"), ("ISVS", "realizuje")],
    "ISVS": [("ISVS", "patri_pod"), ("InfraSluzba", "prevadzkuje"), ("PO", "je_spravca"), ("KRIS", "rozvija"),
             ("PO", "je_prevadzkovatel"), ("AS", "sluzi"), ("Projekt", "realizuje")],
    "Projekt": [("Program", "financuje"), ("Projekt", "asociuje", "Projekt_je_asociovany_s_projektom"), ("Program", "obsahuje"), ("PO", "asociuje")],
    "InfraSluzba": [("Projekt", "realizuje")],
    "ZS": [("PO", "je_partner")],
}

MAX_RETRIES = 10
RETRY_DELAY = 1.0  # seconds

def run_relation(central: str, outer: str, rel: str, reltype_override: str | None = None) -> None:
    if reltype_override:
        cmd = f"run/relation.sh {central} {outer} {rel} {reltype_override} --no-csv"
        label = f"{central}_{rel}_{outer} [{reltype_override}]"
    else:
        cmd = f"run/relation.sh {central} {outer} {rel} --no-csv"
        label = f"{central}_{rel}_{outer}"

    print(f"  -> Generating relation {central} <--({rel})-- {outer}" + (f" (override={reltype_override})" if reltype_override else ""))

    attempt = 1
    while attempt <= MAX_RETRIES:
        try:
            subprocess.run(cmd, shell=True, check=True, text=True)
            print(f"[OK] {label} completed")
            return
        except subprocess.CalledProcessError:
            print(f"[WARN] Failed attempt {attempt}/{MAX_RETRIES} for {label}, retrying in {RETRY_DELAY}s...")
            time.sleep(RETRY_DELAY)
            attempt += 1

    print(f"[ERROR] Giving up on {label}")


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 raw_relations.py <CENTRAL_NODE>")
        print("Example: python3 raw_relations.py KS")
        sys.exit(1)

    central = sys.argv[1]
    if central not in RELATIONS:
        print(f"[ERROR] No relations defined for central node '{central}'")
        sys.exit(1)

    for spec in RELATIONS[central]:
        if len(spec) == 2:
            outer, rel = spec
            run_relation(central, outer, rel)
        elif len(spec) == 3:
            outer, rel, reltype_override = spec
            run_relation(central, outer, rel, reltype_override)
        else:
            print(f"[ERROR] Bad relation spec for {central}: {spec} (expected 2 or 3 items)")


if __name__ == "__main__":
    main()