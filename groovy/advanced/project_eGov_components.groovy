// ver 1.0

// ============================================================================
// Report: Project → eGov components (ISVS, AS, KS) with validations
// Data source: MetaIS / Neo4j
//
// SELECTION
// ---------
// - Primary: parameters.project  (ENUMS_CMDB / typed CMDB UUID) → exact match
// - Secondary: parameters.projID (MetaIS code string) → resolve to the project
// - If neither is present: print a friendly prompt row.
//
// PERFORMANCE
// -----------
// - Scope the single project first.
// - Fetch only its endpoints (ISVS/AS/KS) by project CMDB ID.
// - Build once-per-run relation maps for validations.
// ============================================================================


// ---------- Token dictionaries / truthiness ----------------------------------
// (Used by parseExtIntegrationFlag + dup/compact rendering toggles)
def TRUTHY_SET =   [:];  for (tok in ["1","true","yes","y","áno","ano"])     TRUTHY_SET[(String)tok] =   true
def EMPTY_TOKENS = [:];  for (tok in ["", "null", "none"])                   EMPTY_TOKENS[(String)tok] = true
def YES_TOKENS =   [:];  for (tok in ["áno","ano","yes","true","t","y","1"]) YES_TOKENS[(String)tok] =   true
def NO_TOKENS =    [:];  for (tok in ["nie","no","false","f","n","0"])       NO_TOKENS[(String)tok] =    true
def DUP_SET =      [:];  for (tok in ["true","1","yes","y","áno","ano"])     DUP_SET[(String)tok] =      true

def truthy = { x -> TRUTHY_SET[(String)("" + (x ?: "")).trim().toLowerCase()] == true }


// ---------- Attribute cache (lean, by endpoint UUID) -------------------------
// We seed from rows we already fetched, and (if needed) do a minimal lookup by UUID.
def ATTR_SEEN  = [:]
def ATTR_CACHE = new LinkedHashMap()  // uuid → {uuid, name, meta, isvs_modul?, as_ext?}


// ---------- Utility: small set (LinkedHashSet) in a map ----------------------
def getSet = { Map m, String k ->
  def s = (LinkedHashSet) m[k]
  if (s == null) { s = new LinkedHashSet(); m[k] = s }
  (LinkedHashSet<String>) s
}

def isEmptyList = { lst -> if (lst == null) return true; for (_ in lst) return false; return true }


// ---------- Parse external-integration flag (handles enum / boolean / strings)
def parseExtIntegrationFlag = { raw ->
  def one = { v ->
    if (v == null) return null
    if (v instanceof Boolean) return v
    def s = ("" + v).trim().toLowerCase()
    if (EMPTY_TOKENS[(String)s] == true) return null
    if (s == "c_stav_dost_ext_int.1") return true
    if (s == "c_stav_dost_ext_int.2") return false
    if (YES_TOKENS[(String)s] == true) return true
    if (NO_TOKENS[(String)s]  == true) return false
    return null
  }
  if (raw instanceof List) {
    for (v in (List)raw) if (one(v) == Boolean.TRUE) return true
    return false
  } else {
    def v = one(raw)
    return (v != null) ? v : false
  }
}


// ---------- Lean lookup by UUID (name + metais code) -------------------------
def lookupLeanByUuid = { String typeName, String uuid ->
  if (!uuid) return [null, null]
  def qix = qi("lookup_" + typeName)

  def q = match(path().node(qix, type(typeName)))
    .where(not(qix.filter(state(StateEnum.INVALIDATED))))
    .returns(
      prop("uuid", qix.prop("\$cmdb_id")),
      prop("name", qix.prop("Gen_Profil_nazov")),
      prop("meta", qix.prop("Gen_Profil_kod_metais"))
    )

  def rows = Neo4j.execute(q).data
  for (row in rows) if ((String) row.uuid == (String) uuid) return [ (String) row.name, (String) row.meta ]
  [null, null]
}

def nameMetaWithScope = { String uuid, String typeName ->
  def cached = ATTR_CACHE[uuid]
  if (cached?.name) return [ (String) cached.name, (String) (cached.meta ?: "?"), false ]
  def (n, m) = lookupLeanByUuid(typeName, uuid)
  if (n != null || m != null) return [ "(mimo projektu) " + (n ?: "<bez názvu>"), (m ?: "?"), true ]
  [ "<bez názvu>", "?", false ]
}

def displayNameOnly = { String typeName, String uuid, String name0 ->
  if (name0 && name0 != "<bez názvu>") return name0
  def (nf, _mf, _outside) = nameMetaWithScope(uuid, typeName)
  nf
}


// ---------- Resolve project by MetaIS code (projID -> single row) ------------
def resolveEnumFromMeta = { String metaisCode ->
  if (!metaisCode) return null
  def q = match(path().node(qi("proj_resolve"), type("Projekt")))
    .where(and(
      not(qi("proj_resolve").filter(state(StateEnum.INVALIDATED))),
      qi("proj_resolve").prop("Gen_Profil_kod_metais").filter(eq(metaisCode))
    ))
    .returns(
      prop("pid_typed",           qi("proj_resolve").prop(Id.PROP_ID)),        // typed CMDB id
      prop("project_uuid",        qi("proj_resolve").prop("\$cmdb_id")),
      prop("project_name",        qi("proj_resolve").prop("Gen_Profil_nazov")),
      prop("project_meta",        qi("proj_resolve").prop("Gen_Profil_kod_metais")),
      prop("project_last_mod_at", qi("proj_resolve").prop("\$cmdb_lastModifiedAt"))
    )
    .limit(1)

  def rows = Neo4j.execute(q).data
  return rows.isEmpty() ? null : rows[0]
}


// ---------- Report columns ----------------------------------------------------
def headers = [
  new Header("Projekt",            Header.Type.STRING),
  new Header("ID projektu",        Header.Type.STRING),  // MetaIS code of the project
  new Header("Gestor projektu",    Header.Type.STRING),  // PO name(s)
  new Header("Typ eGov",           Header.Type.STRING),
  new Header("Názov eGov",         Header.Type.STRING),
  new Header("ID eGov",            Header.Type.STRING),  // MetaIS code of the endpoint
  new Header("Chyba eGov",         Header.Type.STRING),
  new Header("ID dotknutého eGov", Header.Type.STRING)   // MetaIS code referenced in the error, if any
]


// ---------- Parameters & UX toggles ------------------------------------------
def enumProject = parameters.project           // typed CMDB UUID
def metaisCode  = parameters.projID as String  // MetaIS project code

def duplicitParam        = ("" + (report.parameters?.dupNames ?: "")).trim().toLowerCase()
def duplicit_name_output = DUP_SET[(String)duplicitParam] == true


// ---------- Scope the project -------------------------------------------------
def resProjectsScoped = []
if (enumProject != null && ("" + enumProject) != "") {
  def q = match(path().node(qi("proj_enum"), type("Projekt")))
    .where(and(
      not(qi("proj_enum").filter(state(StateEnum.INVALIDATED))),
      qi("proj_enum").prop(Id.PROP_ID).filter(eq(enumProject))
    ))
    .returns(
      prop("project_uuid",        qi("proj_enum").prop("\$cmdb_id")),
      prop("project_name",        qi("proj_enum").prop("Gen_Profil_nazov")),
      prop("project_meta",        qi("proj_enum").prop("Gen_Profil_kod_metais")),
      prop("project_last_mod_at", qi("proj_enum").prop("\$cmdb_lastModifiedAt"))
    )
    .limit(1)
  resProjectsScoped = Neo4j.execute(q).data

} else if (metaisCode != null && metaisCode.trim() != "") {
  def hit = resolveEnumFromMeta(metaisCode.trim())
  if (hit != null) {
    resProjectsScoped = [[
      project_uuid        : hit.project_uuid,
      project_name        : hit.project_name,
      project_meta        : hit.project_meta,
      project_last_mod_at : hit.project_last_mod_at
    ]]
  } else {
    def r = new Report(headers)
    r.add(["Vyplň názov projektu alebo kód MetaIS", "", "", "", "", "", "", ""])
    return new ReportResult("TABLE", r, 1)
  }

} else {
  def r = new Report(headers)
  r.add(["Vyplň názov alebo kód MetaIS", "", "", "", "", "", "", ""])
  return new ReportResult("TABLE", r, 1)
}

if (isEmptyList(resProjectsScoped)) return new Report(headers)


// ---------- Fetch endpoints (only for the scoped project) + Gestor -----------
def resISVS = []; def resAS = []; def resKS = []
def GESTOR_BY_PROJECT = new LinkedHashMap<String, String>()

int _pidx = 0
for (p in resProjectsScoped) {
  _pidx++
  def pid = (String) p.project_uuid

  // ISVS realized by the project
  def qISVS = match(
      path().node(qi("proj_isvs_" + _pidx), type("Projekt"))
             .rel(qi("rel_isvs_"  + _pidx), RelationshipDirection.OUT, type("Projekt_realizuje_ISVS"))
             .node(qi("isvs_"      + _pidx), type("ISVS"))
    )
    .where(and(
      not(qi("proj_isvs_" + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("rel_isvs_"  + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("isvs_"      + _pidx).filter(state(StateEnum.INVALIDATED))),
      qi("proj_isvs_" + _pidx).prop("\$cmdb_id").filter(eq(pid))
    ))
    .returns(
      prop("project_uuid",   qi("proj_isvs_" + _pidx).prop("\$cmdb_id")),
      prop("endpoint_uuid",  qi("isvs_"      + _pidx).prop("\$cmdb_id")),
      prop("endpoint_name",  qi("isvs_"      + _pidx).prop("Gen_Profil_nazov")),
      prop("endpoint_meta",  qi("isvs_"      + _pidx).prop("Gen_Profil_kod_metais")),
      prop("isvs_modul",     qi("isvs_"      + _pidx).prop("EA_Profil_ISVS_modul_isvs"))
    )
  resISVS.addAll(Neo4j.execute(qISVS).data)

  // AS realized by the project
  def qAS = match(
      path().node(qi("proj_as_" + _pidx), type("Projekt"))
             .rel(qi("rel_as_"  + _pidx), RelationshipDirection.OUT, type("Projekt_realizuje_AS"))
             .node(qi("as_"      + _pidx), type("AS"))
    )
    .where(and(
      not(qi("proj_as_" + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("rel_as_"  + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("as_"      + _pidx).filter(state(StateEnum.INVALIDATED))),
      qi("proj_as_" + _pidx).prop("\$cmdb_id").filter(eq(pid))
    ))
    .returns(
      prop("project_uuid",   qi("proj_as_" + _pidx).prop("\$cmdb_id")),
      prop("endpoint_uuid",  qi("as_"      + _pidx).prop("\$cmdb_id")),
      prop("endpoint_name",  qi("as_"      + _pidx).prop("Gen_Profil_nazov")),
      prop("endpoint_meta",  qi("as_"      + _pidx).prop("Gen_Profil_kod_metais")),
      prop("as_ext",         qi("as_"      + _pidx).prop("EA_Profil_AS_dostupnost_pre_externu_integraciu"))
    )
  resAS.addAll(Neo4j.execute(qAS).data)

  // KS realized by the project
  def qKS = match(
      path().node(qi("proj_ks_" + _pidx), type("Projekt"))
             .rel(qi("rel_ks_"  + _pidx), RelationshipDirection.OUT, type("Projekt_realizuje_KS"))
             .node(qi("ks_"      + _pidx), type("KS"))
    )
    .where(and(
      not(qi("proj_ks_" + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("rel_ks_"  + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("ks_"      + _pidx).filter(state(StateEnum.INVALIDATED))),
      qi("proj_ks_" + _pidx).prop("\$cmdb_id").filter(eq(pid))
    ))
    .returns(
      prop("project_uuid",   qi("proj_ks_" + _pidx).prop("\$cmdb_id")),
      prop("endpoint_uuid",  qi("ks_"      + _pidx).prop("\$cmdb_id")),
      prop("endpoint_name",  qi("ks_"      + _pidx).prop("Gen_Profil_nazov")),
      prop("endpoint_meta",  qi("ks_"      + _pidx).prop("Gen_Profil_kod_metais"))
    )
  resKS.addAll(Neo4j.execute(qKS).data)

  // Gestor (PO_asociuje_Projekt) for this project
  def qGestor = match(
      path().node(qi("po_gestor_" + _pidx),   type("PO"))
             .rel(qi("rel_gestor_" + _pidx),  RelationshipDirection.OUT, type("PO_asociuje_Projekt"))
             .node(qi("proj_gestor_" + _pidx), type("Projekt"))
    )
    .where(and(
      not(qi("proj_gestor_" + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("rel_gestor_" + _pidx).filter(state(StateEnum.INVALIDATED))),
      not(qi("po_gestor_"  + _pidx).filter(state(StateEnum.INVALIDATED))),
      qi("proj_gestor_" + _pidx).prop("\$cmdb_id").filter(eq(pid))
    ))
    .returns(prop("po_name", qi("po_gestor_" + _pidx).prop("Gen_Profil_nazov")))

  def poRows = Neo4j.execute(qGestor).data
  GESTOR_BY_PROJECT[pid] = (poRows == null || poRows.isEmpty())
    ? ""
    : poRows.collect { (String) it.po_name ?: "" }.findAll { it }.join("; ")
}


// ---------- Relation maps (global, once per run) -----------------------------
def mapRel = { String relTypeName, boolean leftIsCentral, String leftTypeName, String rightTypeName ->
  def qiL   = qi("L_"   + relTypeName)
  def qiR   = qi("R_"   + relTypeName)
  def qiRel = qi("REL_" + relTypeName)

  def p = path()
  if (leftIsCentral) p = p.node(qiL, type(leftTypeName)).rel(qiRel, RelationshipDirection.OUT, type(relTypeName)).node(qiR, type(rightTypeName))
  else               p = p.node(qiL, type(leftTypeName)).rel(qiRel, RelationshipDirection.IN,  type(relTypeName)).node(qiR, type(rightTypeName))

  def q = match(p)
    .where(and(
      not(qiRel.filter(state(StateEnum.INVALIDATED))),
      not(qiL.filter(state(StateEnum.INVALIDATED))),
      not(qiR.filter(state(StateEnum.INVALIDATED)))
    ))
    .returns(
      prop("left_uuid",  qiL.prop("\$cmdb_id")),
      prop("right_uuid", qiR.prop("\$cmdb_id"))
    )

  def rows = Neo4j.execute(q).data
  def m = new LinkedHashMap()
  for (r in rows) {
    def l  = (String) r.left_uuid
    def rr = (String) r.right_uuid
    if (l != null && rr != null) getSet(m, l).add(rr)
  }
  m
}

// AS↦KS and reverse
def AS_SLUZI_KS__AS_to_KS = mapRel("AS_sluzi_KS", true, "AS", "KS")
def AS_SLUZI_KS__KS_to_AS = new LinkedHashMap()
for (entry in AS_SLUZI_KS__AS_to_KS.entrySet()) for (k in (entry.value ?: [])) getSet(AS_SLUZI_KS__KS_to_AS, (String)k).add((String)entry.key)

// Kanal↦KS and reverse
def KANAL_SPRISTUPNUJE_KS = mapRel("Kanal_spristupnuje_KS", true, "Kanal", "KS")
def KS_TO_KANAL = new LinkedHashMap()
for (entry in KANAL_SPRISTUPNUJE_KS.entrySet()) for (k in (entry.value ?: [])) getSet(KS_TO_KANAL, (String)k).add((String)entry.key)

// ZS↦KS and reverse
def ZS_ZOSKUPUJE_KS = mapRel("ZS_zoskupuje_KS", true, "ZS", "KS")
def KS_TO_ZS = new LinkedHashMap()
for (entry in ZS_ZOSKUPUJE_KS.entrySet()) for (k in (entry.value ?: [])) getSet(KS_TO_ZS, (String)k).add((String)entry.key)

// KS↦Agenda
def KS_ASOCIUJE_AGENDA = mapRel("KS_asociuje_Agenda", true, "KS", "Agenda")

// ISVS tree (parent↦child and reverse)
def ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD = mapRel("ISVS_patri_pod_ISVS", true, "ISVS", "ISVS")
def ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT = new LinkedHashMap()
for (entry in ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD.entrySet()) for (child in (entry.value ?: [])) getSet(ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT, (String)child).add((String)entry.key)

// Infra ↦ ISVS and reverse
def INFRA_PREVADZKUJE_ISVS = mapRel("InfraSluzba_prevadzkuje_ISVS", true, "InfraSluzba", "ISVS")
def ISVS_TO_INFRA = new LinkedHashMap()
for (entry in INFRA_PREVADZKUJE_ISVS.entrySet()) for (i in (entry.value ?: [])) getSet(ISVS_TO_INFRA, (String)i).add((String)entry.key)

// ISVS ↦ AS
def ISVS_REALIZUJE_AS__ISVS_TO_AS = mapRel("ISVS_realizuje_AS", true, "ISVS", "AS")

// AS ↦ AS (and reverse)
def AS_SLUZI_AS__AS_TO_AS = mapRel("AS_sluzi_AS", true, "AS", "AS")
def AS_SLUZI_AS__REV = new LinkedHashMap()
for (entry in AS_SLUZI_AS__AS_TO_AS.entrySet()) for (b in (entry.value ?: [])) getSet(AS_SLUZI_AS__REV, (String)b).add((String)entry.key)


// ---------- Seed ATTR_CACHE from fetched endpoint rows -----------------------
for (r in resISVS) { def u=(String)r.endpoint_uuid; if (u && ATTR_SEEN[u]!=true){ ATTR_CACHE[u]=[uuid:u,name:(String)r.endpoint_name,meta:(String)r.endpoint_meta,isvs_modul:r.isvs_modul]; ATTR_SEEN[u]=true } }
for (r in resAS)   { def u=(String)r.endpoint_uuid; if (u && ATTR_SEEN[u]!=true){ ATTR_CACHE[u]=[uuid:u,name:(String)r.endpoint_name,meta:(String)r.endpoint_meta,as_ext:r.as_ext];     ATTR_SEEN[u]=true } }
for (r in resKS)   { def u=(String)r.endpoint_uuid; if (u && ATTR_SEEN[u]!=true){ ATTR_CACHE[u]=[uuid:u,name:(String)r.endpoint_name,meta:(String)r.endpoint_meta];                   ATTR_SEEN[u]=true } }


// ---------- Validation helpers ----------------------------------------------
def AS_BY_PROJECT_SEEN = new LinkedHashMap<String, Boolean>()

def checkKS = { String ksUuid, String projUuid ->
  def errs = []
  def ASs = AS_SLUZI_KS__KS_to_AS[ksUuid]

  if (ASs == null || isEmptyList(ASs)) {
    errs << [msg:"KS nie je služené žiadnou AS", meta:null]
  } else {
    def ok = false
    for (a in (ASs ?: [])) { if (AS_BY_PROJECT_SEEN[(String)a]) { ok = true; break } }
    if (!ok) errs << [msg:"KS nie je slúžené žiadnou AS realizovanou týmto projektom", meta:null]
  }
  if (!KS_TO_KANAL[ksUuid]     || isEmptyList(KS_TO_KANAL[ksUuid])) errs << [msg:"KS nie je sprístupnená žiadnym kanálom",                 meta:null]
  if (!KS_TO_ZS[ksUuid]        || isEmptyList(KS_TO_ZS[ksUuid]))   errs << [msg:"KS nie je zoskupená žiadnou životnou situáciou",         meta:null]
  if (!KS_ASOCIUJE_AGENDA[ksUuid] || isEmptyList(KS_ASOCIUJE_AGENDA[ksUuid])) errs << [msg:"KS neasociuje žiadnu agendu",                 meta:null]
  return errs
}

def checkISVS = { String isvsUuid ->
  def errs = []
  def attrs = ATTR_CACHE[isvsUuid] ?: [:]
  def isModule = truthy(attrs.isvs_modul)
  def parents  = ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT[isvsUuid]
  def children = ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD[isvsUuid]
  def infra    = ISVS_TO_INFRA[isvsUuid]

  if (isModule && (parents == null || isEmptyList(parents))) errs << [msg:"ISVS je modul, ale nemá materský ISVS", meta:null]

  if (isModule && (children != null && !isEmptyList(children))) {
    for (c in children) {
      def (nm, mm, _) = nameMetaWithScope((String)c, "ISVS")
      errs << [msg:"ISVS je modul, ale má dcérske ISVS: " + nm, meta:mm]
    }
  }

  if (!isModule && (parents != null && !isEmptyList(parents))) {
    for (p in parents) {
      def (nm, mm, _) = nameMetaWithScope((String)p, "ISVS")
      errs << [msg:"ISVS nie je modul, ale patrí pod iné ISVS: " + nm, meta:mm]
    }
  }

  if (infra == null || isEmptyList(infra)) errs << [msg:"ISVS nie je prevádzkovaná žiadnou infraštruktúrnou službou", meta:null]
  return errs
}

def checkAS = { String asUuid ->
  def errs = []

  // realized by any ISVS?
  def realized = false
  for (entry in ISVS_REALIZUJE_AS__ISVS_TO_AS.entrySet()) {
    for (x in (entry.value ?: [])) { if ((String)x == (String)asUuid) { realized = true; break } }
    if (realized) break
  }
  if (!realized) errs << [msg:"AS nie je realizovaná žiadnym ISVS", meta:null]

  // external-integration rules
  def asAttrs   = ATTR_CACHE[asUuid] ?: [:]
  def extEnabled = parseExtIntegrationFlag(asAttrs.as_ext)
  if (!extEnabled) {
    def asTargets = AS_SLUZI_AS__AS_TO_AS[asUuid] ?: []
    for (as2 in asTargets) {
      def (nm, mm, _) = nameMetaWithScope((String)as2, "AS")
      errs << [msg:'AS nemá príznak "určená na externú integráciu", ale má vzťah na inú AS: ' + nm, meta:mm]
    }
  }
  def sourcesToMe = AS_SLUZI_AS__REV[asUuid] ?: []
  for (as2 in sourcesToMe) {
    def a2 = ATTR_CACHE[as2] ?: [:]
    def a2Ext = parseExtIntegrationFlag(a2.as_ext)
    if (!a2Ext) {
      def (nm, mm, _) = nameMetaWithScope((String)as2, "AS")
      errs << [msg:'AS má vzťah na inú AS ktorá nemá príznak "určená na externú integráciu": ' + nm, meta:mm]
    }
  }
  return errs
}


// ---------- Report building ---------------------------------------------------
def report = new Report(headers)
def totalRows = 0

for (p in resProjectsScoped) {
  final String pid        = p.project_uuid
  final String pname      = p.project_name ?: "<bez názvu>"
  final String pmeta      = p.project_meta ?: "?"
  final String gestorName = GESTOR_BY_PROJECT[pid] ?: ""

  // Collect endpoints for this project only
  def entriesISVS = new ArrayList()
  for (r in resISVS) if (r.project_uuid == pid && r.endpoint_uuid != null) entriesISVS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  def entriesAS = new ArrayList()
  for (r in resAS)   if (r.project_uuid == pid && r.endpoint_uuid != null) entriesAS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  def entriesKS = new ArrayList()
  for (r in resKS)   if (r.project_uuid == pid && r.endpoint_uuid != null) entriesKS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  // If the project has no endpoints at all, print a single “no services” row.
  if (isEmptyList(entriesISVS) && isEmptyList(entriesAS) && isEmptyList(entriesKS)) {
    report.add([pname, pmeta, gestorName, "", "(Projekt nerealizuje žiadne služby)", "", "", ""])
    totalRows++
    continue
  }

  // For KS validations we need to know which AS belong to this project
  AS_BY_PROJECT_SEEN.clear()
  for (row in entriesAS) {
    def asId = (String) row[0]
    if (asId != null) AS_BY_PROJECT_SEEN[asId] = true
  }

  boolean printedProject = false
  def blocks = [
    ["ISVS", entriesISVS],
    ["AS",   entriesAS],
    ["KS",   entriesKS]
  ]

  // Row appender with dup-name logic and multi-error expansion
  def addComponentRows = { projNameCell, projMetaCell, projGestorCell, lbl, compName, compMeta, errObjs ->
    if (errObjs == null || isEmptyList(errObjs)) {
      report.add([projNameCell, projMetaCell, projGestorCell, lbl, compName, compMeta, "", ""])
      totalRows++
      return
    }
    // First error on same row
    report.add([projNameCell, projMetaCell, projGestorCell, lbl, compName, compMeta, errObjs[0].msg ?: "", errObjs[0].meta ?: ""])
    totalRows++
    // Additional errors spread on following rows
    for (int e = 1; e < errObjs.size(); e++) {
      report.add([
        duplicit_name_output ? projNameCell   : "",
        duplicit_name_output ? projMetaCell   : "",
        duplicit_name_output ? projGestorCell : "",
        duplicit_name_output ? lbl            : "",
        duplicit_name_output ? compName       : "",
        duplicit_name_output ? compMeta       : "",
        errObjs[e].msg  ?: "",
        errObjs[e].meta ?: ""
      ])
      totalRows++
    }
  }

  for (blk in blocks) {
    final String label   = (String) blk[0]
    final def    entries = (blk[1] ?: [])

    if (isEmptyList(entries)) {
      // No components of this label → 1 row informing about absence of this type
      def projNameCell = duplicit_name_output ? pname      : (printedProject ? "" : pname)
      def projCodeCell = duplicit_name_output ? pmeta      : (printedProject ? "" : pmeta)
      def projGestCell = duplicit_name_output ? gestorName : (printedProject ? "" : gestorName)

      report.add([
        projNameCell,
        projCodeCell,
        projGestCell,
        label,
        (label == "ISVS" ? "(Projekt nerealizuje žiaden informačný systém verejnej správy)"
                         : label == "AS"   ? "(Projekt nerealizuje žiadnu aplikačnú službu)"
                                           : "(Projekt nerealizuje žiadnu koncovú službu)"),
        "",
        "",
        ""
      ])
      totalRows++; printedProject = true
      continue
    }

    printedLabel = false
    for (trip in entries) {
      final String endpointUuid = (String) trip[0]
      final String endpointName = (String) trip[1]
      final String endpointMeta = (String) (trip[2] ?: "")

      final String compName = displayNameOnly(label, endpointUuid, endpointName)
      final String compMeta = endpointMeta

      final def errObjs = (label == "KS") ? checkKS(endpointUuid, pid)
                         : (label == "ISVS") ? checkISVS(endpointUuid)
                         : checkAS(endpointUuid)

      final String projNameCell = duplicit_name_output ? pname      : (printedProject ? "" : pname)
      final String projCodeCell = duplicit_name_output ? pmeta      : (printedProject ? "" : pmeta)
      final String projGestCell = duplicit_name_output ? gestorName : (printedProject ? "" : gestorName)
      final String lbl =          duplicit_name_output ? label    :   (printedLabel  ?  "" : label)

      addComponentRows(projNameCell, projCodeCell, projGestCell, lbl, compName, compMeta, errObjs)
      printedProject = true
      printedLabel = true
    }
  }
}


// ---------- Finalize ----------------------------------------------------------
def result = new ReportResult("TABLE", report, totalRows)
return result