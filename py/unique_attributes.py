#!/usr/bin/env python3
import argparse
import json
import os
from collections import Counter
from typing import Dict, List, Tuple, Any, Optional, Set

# --------------------------------------- Utilities ---------------------------------------
# Resolve a token to a JSON file path under output/. Supports:
# - "KS_raw"  -> "output/KS_raw.json"
# - "KS"      -> "output/KS_raw.json"
# - "output/KS_raw.json" (already a JSON path) -> unchanged
def resolve_path_nod(token: str) -> str:
    token = token.strip()

    # If it's already a .json path (absolute or relative), return as-is
    if token.lower().endswith(".json"):
        return token

    # If user just wrote "KS", automatically interpret it as "KS_raw"
    if "_" not in token:     # e.g. no underscore — such as "KS", "PO", "AS"
        token = f"{token}_raw"

    # Map to "output/<token>.json"
    return os.path.join("output/nodes", f"{token}.json")

def resolve_path_rel(token: str) -> str:
    token = token.strip()

    # If it's already a .json path (absolute or relative), return as-is
    if token.lower().endswith(".json"):
        return token

    # If user just wrote "KS", automatically interpret it as "KS_raw"
    if "_" not in token:     # e.g. no underscore — such as "KS", "PO", "AS"
        token = f"{token}_raw"

    # Map to "output/<token>.json"
    return os.path.join("output/relations", f"{token}.json")

def load_json(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

# all raw files start with
# [
#   "page",
#   "perPage",
#   "result",
#   "totalCount",
#   "type"
# ]
# and the actual payload is inside "result": (meow)
# so we take that out to pre-process the data
def get_result_array(doc: Any) -> List[Dict[str, Any]]:
    # Return the list of objects from a raw dump (envelope or plain list).
    if isinstance(doc, dict) and isinstance(doc.get("result"), list):
        return doc["result"]
    if isinstance(doc, list):
        return doc
    raise ValueError("Unrecognized raw JSON format: expected envelope with 'result' or a list.")

# each entry in the payload is the same.
# it contains "uuid" (unique identifier of the entry), "type" (type of the node, i.e. KS, AS, ZS, Projekt, ...), "attributes" and "metaAttributes"
# main attributes of each entry are in "attributes"
# it is a list of { "name": , "value": }
# "name" is the machine name of the attribute, i.e. the name of the thing is stored in "value" corresponding to "name": "Gen_Profil_nazov"
# { "name": "Gen_Profil_nazov", "value": "Publikovanie informácií o výkone pozemkových úprav"}
# in this function we look at how many attributes of the same kind appear across the dataset: i.e. if we expect all entries to be named, then Gen_Profil_nazov should appear everywhere.
def count_attribute_presence(objs: List[Dict[str, Any]]) -> Tuple[Counter, int]:
    # Count unique attribute 'name' presence across objects (per-object set) and return (counter, total).
    total = len(objs)
    counter = Counter()
    for o in objs:
        names = {a.get("name") for a in o.get("attributes", []) if a.get("name")}
        counter.update(names)
    return counter, total

# here we print out the counts and frequency (%) of each attribute sorted in descending order by their frequency
# i.e. Gen_Profil_nazov will be most likely near the top because it appears in all entries
# obscure stuff like KS_Profil_UPVS_KS_Profil_UPVS_podporovany_operacny_system or Service_Heartbeat_Status will appear near the bottom cuz who even uses that, right?
def print_attr_table(counter: Counter, total: int, title: Optional[str] = None) -> None:
    if title:
        print(title)
    print(f"Total objects in dataset: {total}\n")
    print(f"{'Attribute Name':70} {'Count':>7} {'% of Dataset':>12}")
    print("-" * 91)
    rows = sorted(
        ((name, cnt, (cnt / total * 100.0 if total else 0.0)) for name, cnt in counter.items()),
        key=lambda x: x[2],
        reverse=True,
    )
    for name, cnt, pct in rows:
        print(f"{name:70} {cnt:7d} {pct:11.3f}%")
    print()

# here we create a map (dictionary in python)
# that maps from a unique identifier (uuid, key)
# to the value being the json portion of the data entry where that uuid appears
# the data entry is also a map/dictionary, so the syntax is str -> Dict[str, Any], don't get confused
def build_uuid_index(raw_doc: Any) -> Dict[str, Dict[str, Any]]:
    # Map top-level uuid → object.
    idx: Dict[str, Dict[str, Any]] = {}
    for o in get_result_array(raw_doc):
        u = o.get("uuid")
        if u:
            idx[u] = o
    return idx

# -------------------------------------- Relations --------------------------------------
#    Parse a relation TABLE JSON where each row ties an entry in the central dataset to
#    an entry in an endpoint dataset.
#
#    Expected structure:
#    {
#      "type": "TABLE",
#      "result": {
#        "headers": [
#          {"name": "central_uuid", "type": "STRING"},
#          {"name": "outer_uuid",   "type": "STRING"}
#        ],
#        "rows": [
#          {"values": ["<central-uuid>", "<outer-uuid>"]},
#          ...
#        ]
#      }
#    }
#
#    Returns:
#        (central_header_name, endpoint_header_name, [(central_uuid, endpoint_uuid), ...])
def parse_relation_table_ids(doc: Dict[str, Any]) -> Tuple[str, str, List[Tuple[str, str]]]:
    result = doc.get("result", {})
    headers = result.get("headers", [])
    rows = result.get("rows", [])

    if len(headers) < 2:
        raise ValueError("Relation TABLE must have at least two headers (<central>_uuid, <endpoint>_uuid).")

    h0 = (headers[0].get("name") or "").strip()
    h1 = (headers[1].get("name") or "").strip()

    # Mild validation
    if not h0.lower().endswith("_uuid") or not h1.lower().endswith("_uuid"):
        raise ValueError(f"Expected first two headers to be '*_uuid'. Got: '{h0}', '{h1}'")

    pairs: List[Tuple[str, str]] = []
    for r in rows:
        vals = r.get("values", [])
        if len(vals) < 2:
            continue
        ks_u = vals[0]
        ep_u = vals[1]
        if ks_u and ep_u:
            pairs.append((str(ks_u).strip(), str(ep_u).strip()))

    return h0, h1, pairs

# this is tied to how the script is called.
# python3 py/unique_attributes.py <central> - just stats the central file
# any relations are passed as arguments after the <centra> filename as:
# <(endpoint)_relation_(central)>,<endpoint>
# ex: python3 py/unique_attributes.py KS_raw PO_je_gestor_KS,PO_raw Projekt_realizuje_KS,Projekt_raw
# there should be no space around the comma!
# Returns list of tuples: (relation_title, relation_path, endpoint_raw_path)
def parse_relation_specs(specs: List[str]) -> List[Tuple[str, str, str]]:
    parsed: List[Tuple[str, str, str]] = []
    for token in specs:
        parts = [p.strip() for p in token.split(",") if p.strip()]
        if len(parts) != 2:
            raise ValueError(f"Relation spec must be 'relationJson,endpointRawJson' but got: {token}")
        rel_tok, raw_tok = parts
        rel_path = resolve_path_rel(rel_tok)
        raw_path = resolve_path_nod(raw_tok)
        title = os.path.splitext(os.path.basename(rel_path))[0]
        parsed.append((title, rel_path, raw_path))
    return parsed

# ---------- Main ----------
def main():
    # this is for --help
    ap = argparse.ArgumentParser(
        description="Compute attribute coverage for a central raw JSON, plus matched endpoint sets from UUID-based relation TABLEs."
    )
    ap.add_argument("central", help="Central raw JSON (e.g., KS_raw or output/KS_raw.json)")
    ap.add_argument("relations", nargs="*", help="Zero or more relation specs 'RelationTable,EndpointRaw'")
    args = ap.parse_args()

    central_path = resolve_path_nod(args.central)
    if not os.path.exists(central_path):
        raise FileNotFoundError(f"Central file not found: {central_path}")

    central_doc = load_json(central_path)
    central_objs = get_result_array(central_doc)
    central_uuid_index = build_uuid_index(central_doc)

    # Central attribute coverage
    central_counter, central_total = count_attribute_presence(central_objs)
    print_attr_table(central_counter, central_total, title=f"======== {os.path.basename(central_path)} (central) ========")

    # Each relation
    for title, rel_path, endpoint_raw_path in parse_relation_specs(args.relations):
        if not os.path.exists(rel_path):
            raise FileNotFoundError(f"Relation TABLE not found: {rel_path}")
        if not os.path.exists(endpoint_raw_path):
            raise FileNotFoundError(f"Endpoint RAW not found: {endpoint_raw_path}")

        rel_doc = load_json(rel_path)
        central_col, endpoint_col, pairs = parse_relation_table_ids(rel_doc)

        endpoint_doc = load_json(endpoint_raw_path)
        endpoint_uuid_index = build_uuid_index(endpoint_doc)

        # Keep only endpoints whose central_uuid exists in the central file
        endpoint_ids: Set[str] = set(ep for ks, ep in pairs if ks in central_uuid_index)

        matched_endpoints = [endpoint_uuid_index[u] for u in endpoint_ids if u in endpoint_uuid_index]
        ep_counter, ep_total = count_attribute_presence(matched_endpoints)

        pretty_title = f"-------- {title} ({central_col} → {endpoint_col}) ---------"
        print_attr_table(ep_counter, ep_total, title=pretty_title)
        print(f"[INFO] {title}: relation rows = {len(pairs)}, joined endpoints = {len(endpoint_ids)}, hydrated = {len(matched_endpoints)}.\n")

if __name__ == "__main__":
    main()