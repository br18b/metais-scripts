import json, os
from collections import defaultdict # C++-like multiset
import pandas as pd # for excel tables

dir_relations = "./output/relations/"
dir_nodes = "./output/nodes/"

# ------------- Containers -------------

# node_by_id[node_name][uuid] = attributes (flattened)
node_by_id = defaultdict(dict)

# quick reverse index for type filtering: node_type_by_uuid[uuid] = "Projekt"/"ISVS"/...
node_type_by_uuid = {}

# Relation maps keyed by the WHOLE relation name (robust & unambiguous)
# rel_central_outer[rel_name][central_uuid] -> {outer_uuid, ...}
# rel_outer_central[rel_name][outer_uuid]  -> {central_uuid, ...}
rel_central_outer = defaultdict(lambda: defaultdict(set))
rel_outer_central = defaultdict(lambda: defaultdict(set))

# ------------- Setup -------------
node_names = ["Projekt", "ISVS", "KS", "AS", "ZS"]
rela_names = ["Projekt_realizuje_ISVS", "Projekt_realizuje_KS", "Projekt_realizuje_AS", # basic 3
              "AS_sluzi_KS", "Kanal_spristupnuje_KS", "ZS_zoskupuje_KS", "KS_asociuje_Agenda", # KS errors
              "ISVS_patri_pod_ISVS", "InfraSluzba_prevadzkuje_ISVS", # ISVS errors
              "ISVS_realizuje_AS", "AS_sluzi_AS" # AS errors
            ]

# ------------- Utils -------------
def flatten_attrs(attr_list):
    out = {}
    for a in attr_list or []:
        k, v = a.get("name"), a.get("value")
        if k is None:
            continue
        if k in out:
            if not isinstance(out[k], list):
                out[k] = [out[k]]
            out[k].append(v)
        else:
            out[k] = v
    return out

def get_name(node_type: str, uuid: str) -> str:
    return node_by_id.get(node_type, {}).get(uuid, {}).get("Gen_Profil_nazov")

def get_metais(node_type: str, uuid: str) -> str:
    return node_by_id.get(node_type, {}).get(uuid, {}).get("Gen_Profil_kod_metais")

# ------------- Load nodes -------------
for name in node_names:
    with open(f"{dir_nodes}{name}_raw.json", "r", encoding="utf-8") as f:
        result = json.load(f)["result"]  # this is a LIST
        for data in result:
            uuid = data["uuid"]
            attrs = flatten_attrs(data.get("attributes", []))
            node_by_id[name][uuid] = attrs
            node_type_by_uuid[uuid] = name

# helper for external integration flag
def parse_ext_integration_flag(raw):
    """
    Returns True/False for 'dostupnost pre externu integraciu'.
    Accepts:
      - enum codes: 'c_stav_dost_ext_int.1' (Yes), '... .2' (No)
      - localized labels: 'Áno'/'Ano'/'Nie'
      - booleans: True/False
      - strings: 'true'/'false', 'yes'/'no', '1'/'0', 't'/'f', 'y'/'n'
      - integers: 1/0
      - lists: evaluates True if ANY element parses True
    Unknown/None -> False (conservative)
    """
    C_YES = "c_stav_dost_ext_int.1"
    C_NO  = "c_stav_dost_ext_int.2"

    def _one(x):
        if x is None:
            return None
        if isinstance(x, bool):
            return x
        if isinstance(x, (int, float)):
            return bool(int(x))
        s = str(x).strip().lower()
        if s in ("", "null", "none"):
            return None
        if s == C_YES.lower():
            return True
        if s == C_NO.lower():
            return False
        if s in ("áno", "ano", "yes", "true", "t", "y", "1"):
            return True
        if s in ("nie", "no", "false", "f", "n", "0"):
            return False
        return None  # unknown form

    if isinstance(raw, list):
        # If any element clearly says Yes, treat as True; if any says No and none say Yes, False; else fallback
        flags = [_one(x) for x in raw]
        if any(v is True for v in flags):
            return True
        if all(v is False for v in flags if v is not None):
            return False
        return False  # ambiguous -> conservative
    else:
        v = _one(raw)
        return bool(v) if v is not None else False

# ------------- Load relations -------------
# Expected row format:
# {
#   "values": [
#      "<central_uuid>",  # values[0]
#      "<outer_uuid>"     # values[1]
#   ]
# }
for rel_name in rela_names:
    with open(f"{dir_relations}{rel_name}.json", "r", encoding="utf-8") as f:
        rows = json.load(f).get("result", {}).get("rows", [])

    for row in rows:
        vals = row.get("values", [])
        if len(vals) < 2:
            continue
        central_uuid, outer_uuid = vals[0], vals[1]

        # Store by whole relation name (no assumptions about which type is central/outer)
        rel_central_outer[rel_name][central_uuid].add(outer_uuid)
        rel_outer_central[rel_name][outer_uuid].add(central_uuid)

print("Node counts by type:", {t: len(d) for t, d in node_by_id.items()})
print("Total UUIDs with known type:", len(node_type_by_uuid))

rows = []

relation_specs = [
    ("Projekt_realizuje_KS",   "KS",   "(Projekt nerealizuje žiadnu koncovú službu)"),
    ("Projekt_realizuje_ISVS", "ISVS", "(Projekt nerealizuje žiaden informačný systém verejnej správy)"),
    ("Projekt_realizuje_AS",   "AS",   "(Projekt nerealizuje žiadnu aplikačnú službu)"),
]

def check_KS(KS_uuid, proj_uuid):
    res = [] # list of errors
    # possible error1: Ak KS nemá vzťah "AS slúži KS" so žiadnou AS
    list_of_AS = rel_central_outer["AS_sluzi_KS"].get(KS_uuid, set())
    if not list_of_AS:
        res.append("KS nie je služené žiadnou AS")
    else: # possible error2: Ak KS nemá vzťah "AS slúži KS" so žiadnou AS, realizovanou týmto projektom
        found_match = False
        list_of_AS_proj = rel_outer_central["Projekt_realizuje_AS"].get(proj_uuid, set())
        for AS_uuid in list_of_AS:
            if AS_uuid in list_of_AS_proj:
                found_match = True
                break
        if not found_match:
            res.append("KS nie je slúžené žiadnou AS realizovanou týmto projektom")
    # possible error3: Ak KS nemá vzťah "Kanal spristupnuje KS"
    list_of_Kanal = rel_central_outer["Kanal_spristupnuje_KS"].get(KS_uuid, set())
    if not list_of_Kanal:
        res.append("KS nie je sprístupnená žiadnym kanálom")
    # possible error4: Ak KS nemá vzťah "ZS zoskupuje KS"
    list_of_ZS = rel_central_outer["ZS_zoskupuje_KS"].get(KS_uuid, set())
    if not list_of_ZS:
        res.append("KS nie je zoskupena žiadnou životnou situáciou")
    # possible error5: Ak KS nemá vzťah "KS asociuje Agenda"
    list_of_Agenda = rel_outer_central["KS_asociuje_Agenda"].get(KS_uuid, set())
    if not list_of_Agenda:
        res.append("KS neasociuje žiadnu agendu")

    return res

def check_ISVS(ISVS_uuid):
    res = []

    ISVS_attrs = node_by_id.get("ISVS", {}).get(ISVS_uuid, {})
    # possible error1: Ak ISVS má príznak Modul a nemá vzťah na materský ISVS ("EA_Profil_ISVS_modul_isvs" is true, no "ISVS_patri_pod_ISVS")
    raw_modul = ISVS_attrs.get("EA_Profil_ISVS_modul_isvs", "false") # fallback is false, but all ISVS have this attribute defined
    is_module = str(raw_modul).strip().lower() in ("1", "true", "yes")
    child_links = rel_outer_central["ISVS_patri_pod_ISVS"].get(ISVS_uuid, set()) # here the ISVS is the central one (parent!)
    parent_links = rel_central_outer["ISVS_patri_pod_ISVS"].get(ISVS_uuid, set()) # here this ISVS is the outer one (child)
    infra_links = rel_central_outer["InfraSluzba_prevadzkuje_ISVS"].get(ISVS_uuid, set())
    if is_module and not parent_links:
            res.append("ISVS je modul, ale nemá materský ISVS")
    # possible error2: Ak ISVS má príznak Modul a má opačný vzťah na materský ISVS
    if is_module:
        for child_ISVS_uuid in child_links:
            child_ISVS_attrs = node_by_id.get("ISVS", {}).get(child_ISVS_uuid, {})
            res.append("ISVS je modul, ale má dcérske ISVS: " + child_ISVS_attrs.get("Gen_Profil_nazov") + " (" + child_ISVS_attrs.get("Gen_Profil_kod_metais") + ")")
    # possible error3: Ak ISVS nie je Modul a má vzťah na iný ISVS, ktorý nie je Modul
    if not is_module:
        for parent_ISVS_uuid in parent_links:
            parent_ISVS_attrs = node_by_id.get("ISVS", {}).get(parent_ISVS_uuid, {})
            res.append("ISVS nie je modul, ale patri pod iné ISVS: " + parent_ISVS_attrs.get("Gen_Profil_nazov") + " (" + parent_ISVS_attrs.get("Gen_Profil_kod_metais") + ")")
    # possible error4: Ak ISVS nemá vzťah na žiadnu infraštruktúrnu službu
    if not infra_links:
        res.append("ISVS nie je prevádzkovaná žiadnou infraštruktúrnou službou")

    return res

def check_AS(AS_uuid):
    res = []

    AS_attrs = node_by_id.get("AS", {}).get(AS_uuid, {})
    # possible error1: Ak AS nemá vzťah "ISVS realizuje AS" so žiadnym ISVS
    links_ISVS = rel_central_outer["ISVS_realizuje_AS"].get(AS_uuid, set())
    if not links_ISVS:
        res.append("AS nie je realizovaná žiadnym ISVS")
    # possible error2: Ak AS (dodávaná v projekte) má vzťah "AS slúži AS" so službou AS2 a služba AS je zdroj a nemá príznak "určená na externú integráciu"
    links_AS2 = rel_outer_central["AS_sluzi_AS"].get(AS_uuid, set()) # "AS je zdroj", outer = AS, central = AS2
    # "DOSTUPNOST_PRE_EXTERNU_INTEGRACIU": {
    #   "c_stav_dost_ext_int.1": "Áno",
    #   "c_stav_dost_ext_int.2": "Nie"
    # }
    raw_source = AS_attrs.get("EA_Profil_AS_dostupnost_pre_externu_integraciu") # this attribute is MISSED IN 30% of AS!
    ext_enabled = parse_ext_integration_flag(raw_source)
    if not ext_enabled: # prerobene aby dal aj vsetky ine AS2
        for AS2_uuid in links_AS2:
            AS2_attrs = node_by_id.get("AS", {}).get(AS2_uuid, {})
            as2_name = AS2_attrs.get("Gen_Profil_nazov")
            as2_code = AS2_attrs.get("Gen_Profil_kod_metais")
            res.append("AS nema príznak \"určená na externú integráciu\", ale má vzťah na inú AS: " + as2_name + " (" + as2_code + ")")
    # possible error3: Ak AS (dodávaná v projekte) má vzťah "AS slúži AS" so službou AS2 a služba AS2 je zdroj a AS2 nemá príznak "určená na externú integráciu"
    links_AS2 = rel_central_outer["AS_sluzi_AS"].get(AS_uuid, set()) # "AS2 je zdroj", central = AS, outer = AS2
    for AS2_uuid in links_AS2:
        AS2_attrs = node_by_id.get("AS", {}).get(AS2_uuid, {})
        raw_source = AS2_attrs.get("EA_Profil_AS_dostupnost_pre_externu_integraciu")
        ext_enabled = parse_ext_integration_flag(raw_source)
        if not ext_enabled:
            as2_name = AS2_attrs.get("Gen_Profil_nazov") or "<bez názvu>"
            as2_code = AS2_attrs.get("Gen_Profil_kod_metais") or "?"
            res.append("AS ma vzťah na inú AS ktorá nemá príznak \"určená na externú integráciu\": " + as2_name + " (" + as2_code + ")")

    return res

rows_all = []
rows_by_proj = {}  # proj_uuid -> list-of-rows

COLS = [
    "Projekt",
    "typ eGov komponentu",
    "eGov komponent",
    "Chyby v eGov komponentoch"
]

def add_row(proj_uuid, row):
    rows_all.append(row)
    rows_by_proj.setdefault(proj_uuid, []).append(row)

duplicit_name_output = False

for proj_uuid, proj_attrs in node_by_id["Projekt"].items():
    proj_name = proj_attrs.get("Gen_Profil_nazov", proj_uuid)
    proj_metais = proj_attrs.get("Gen_Profil_kod_metais", proj_uuid)
    proj_name_and_meta = proj_name + " (" + proj_metais + ")"
    col1_first = True

    for rel_name, comp_type, fallback_text in relation_specs:
        col2_first = True
        linked_centrals = rel_outer_central[rel_name].get(proj_uuid, set())

        if not linked_centrals:
            add_row(proj_uuid, [
                proj_name_and_meta if (duplicit_name_output or col1_first) else "",
                comp_type if (duplicit_name_output or col2_first) else "",
                fallback_text,
                ""
            ])
            col1_first = False
            col2_first = False
        else:
            col2_first = True
            for central_uuid in sorted(linked_centrals):

                if comp_type == "KS":
                    errs = check_KS(central_uuid, proj_uuid)
                elif comp_type == "ISVS":
                    errs = check_ISVS(central_uuid)
                elif comp_type == "AS":
                    errs = check_AS(central_uuid)
                else:
                    errs = []

                comp_name = get_name(comp_type, central_uuid)
                comp_meta = get_metais(comp_type, central_uuid)
                comp_name_and_meta = comp_name + " (" + comp_meta + ")"

                col3_first = True

                if not errs:
                    add_row(proj_uuid, [
                        proj_name_and_meta if (duplicit_name_output or col1_first) else "",
                        comp_type if (duplicit_name_output or col2_first) else "",
                        comp_name_and_meta if (duplicit_name_output or col3_first) else "",
                        ""
                    ])
                    col1_first = False
                    col2_first = False
                    col3_first = False
                else:
                    for err in errs:
                        add_row(proj_uuid, [
                            proj_name_and_meta if (duplicit_name_output or col1_first) else "",
                            comp_type if (duplicit_name_output or col2_first) else "",
                            comp_name_and_meta if (duplicit_name_output or col3_first) else "",
                            err
                        ])
                        col1_first = False
                        col2_first = False
                        col3_first = False

# ---- Write the big combined Excel ----
os.makedirs("output/eGov_components_check", exist_ok=True)
table_all = pd.DataFrame(rows_all, columns=COLS)
table_all.to_excel("output/eGov_components_check/all.xlsx", index=False)

# ---- Write one Excel per project UUID ----
per_proj_dir = "output/eGov_components_check/by_project"
os.makedirs(per_proj_dir, exist_ok=True)

for proj_uuid, proj_rows in rows_by_proj.items():
    df = pd.DataFrame(proj_rows, columns=COLS)
    df.to_excel(os.path.join(per_proj_dir, f"{proj_uuid}.xlsx"), index=False)