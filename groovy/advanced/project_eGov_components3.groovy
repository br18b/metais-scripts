String foldSk(String s) {
  if (!s) return ""
  def t = s.toLowerCase()  // avoid Locale.ROOT to not trip the delicate SecureAST
  // Slovak/Czech diacritics → ASCII
  t = t
    .replaceAll("[áäâàãåā]", "a")
    .replaceAll("[čćĉċ]",    "c")
    .replaceAll("[ďđ]",      "d")
    .replaceAll("[éěêèëē]",  "e")
    .replaceAll("[íîìïī]",   "i")
    .replaceAll("[ĺľ]",      "l")
    .replaceAll("[ňñń]",     "n")
    .replaceAll("[óôòõöō]",  "o")  // map Slovak ô→o for matching
    .replaceAll("[ŕř]",      "r")
    .replaceAll("[šśŝș]",    "s")
    .replaceAll("[ťț]",      "t")
    .replaceAll("[úůûùüū]",  "u")
    .replaceAll("[ýŷÿ]",     "y")
    .replaceAll("[žźż]",     "z")
  return t
}

def TRUTHY_SET = [:]
for (tok in ["1","true","yes","y","áno","ano"]) TRUTHY_SET[(String)tok] = true

def EMPTY_TOKENS = [:]
for (tok in ["", "null", "none"]) EMPTY_TOKENS[(String)tok] = true

def YES_TOKENS = [:]
for (tok in ["áno","ano","yes","true","t","y","1"]) YES_TOKENS[(String)tok] = true

def NO_TOKENS = [:]
for (tok in ["nie","no","false","f","n","0"]) NO_TOKENS[(String)tok] = true

def DUP_SET = [:]
for (tok in ["true","1","yes","y","áno","ano"]) DUP_SET[(String)tok] = true

// Attributes cache for ISVS/AS/KS (name/meta also used for messages)
def ATTR_SEEN = [:]
def ATTR_CACHE = new LinkedHashMap() // Map<String uuid, Map<String,String>>

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
    boolean anyTrue = false
    boolean allFalseOrNull = true
    for (v in (List)raw) {
      def o = one(v)
      if (o == Boolean.TRUE) { anyTrue = true; break }
      if (o != null && o != Boolean.FALSE) allFalseOrNull = false
    }
    if (anyTrue) return true
    if (allFalseOrNull) return false
    return false
  } else {
    def v = one(raw)
    return (v != null) ? v : false
  }
}

def truthy = { x ->
  def s = ("" + (x ?: "")).trim().toLowerCase()
  TRUTHY_SET[(String)s] == true
}

// Map<String, Set<String>> helpers (no withDefault, no GDK)
def getSet = { Map m, String k ->
  def s = (LinkedHashSet) m[k]      // avoid Map.get(...)
  if (s == null) {
    s = new LinkedHashSet()
    m[k] = s                        // bracket assignment
  }
  return (LinkedHashSet<String>) s
}

// Lazy, lean fallback lookup for a single node by UUID within a given label.
// NOTE: We return only a few props; we do NOT expand relationships.
def lookupLeanByUuid = { String typeName, String uuid ->
  if (!uuid) return [null, null]
  def qix = qi("lookup_" + typeName)      // distinct per label
  def tx  = type(typeName)

  def q = match(path().node(qix, tx))
    .where(not(qix.filter(state(StateEnum.INVALIDATED))))
    .returns(
      prop("uuid", qix.prop("\$cmdb_id")),
      prop("name", qix.prop("Gen_Profil_nazov")),
      prop("meta", qix.prop("Gen_Profil_kod_metais"))
    )

  def rows = Neo4j.execute(q).data
  for (row in rows) {
    if ((String) row.uuid == (String) uuid) {
      return [ (String) row.name, (String) row.meta ]
    }
  }
  return [null, null]
}

// Return display name/meta for UUID, prefixed with "(mimo projektu) " if resolved via fallback.
def nameMetaWithScope = { String uuid, String typeName ->
  def cached = ATTR_CACHE[uuid]
  if (cached?.name) {
    return [ (String) cached.name, (String) (cached.meta ?: "?"), false ]
  }
  // Fallback: resolve directly from the full label space
  def (n, m) = lookupLeanByUuid(typeName, uuid)
  if (n != null || m != null) {
    return [ "(mimo projektu) " + (n ?: "<bez názvu>"), (m ?: "?"), true ]
  }
  return [ "<bez názvu>", "?", false ]
}

// Build a component display "(Name (meta))", using fallback only if we'd print "<bez názvu>".
def displayComp = { String typeName, String uuid, String name0, String meta0 ->
  def name = name0 ?: "<bez názvu>"
  def meta = meta0 ?: "?"
  if (name != "<bez názvu>") return name + " (" + meta + ")"
  def (nf, mf, _) = nameMetaWithScope(uuid, typeName)
  return nf + " (" + mf + ")"
}

def getName = { uuid -> (ATTR_CACHE[uuid]?.name) ?: "<bez názvu>" }
def getMeta = { uuid -> (ATTR_CACHE[uuid]?.meta) ?: "?" }

def headers = [
  new Header("Projekt",                   Header.Type.STRING),
  new Header("typ eGov komponentu",       Header.Type.STRING),
  new Header("eGov komponent",            Header.Type.STRING),
  new Header("Chyby v eGov komponentoch", Header.Type.STRING)
]

// parameters
def project_name = report.parameters?.projName as String
def project_uuid = report.parameters?.projID as String

def duplicitParam = ("" + (report.parameters?.dupNames ?: "")).trim().toLowerCase()
def duplicit_name_output = DUP_SET[(String)duplicitParam] == true

def qi_proj = qi("proj")
def qi_isvs = qi("isvs")
def qi_as   = qi("as")
def qi_ks   = qi("ks")

def qi_rel_isvs = qi("rel_isvs")
def qi_rel_as   = qi("rel_as")
def qi_rel_ks   = qi("rel_ks")

def type_proj = type("Projekt")
def type_isvs = type("ISVS")
def type_as   = type("AS")
def type_ks   = type("KS")

def type_proj_rel_isvs = type("Projekt_realizuje_ISVS")
def type_proj_rel_as   = type("Projekt_realizuje_AS")
def type_proj_rel_ks   = type("Projekt_realizuje_KS")

def baseMatch = match(path().node(qi_proj, type_proj))
  .where(not(qi_proj.filter(state(StateEnum.INVALIDATED))))

def matchISVS = baseMatch.match(
  path()
    .node(qi_proj)
    .rel(qi_rel_isvs, RelationshipDirection.OUT, type_proj_rel_isvs)
    .node(qi_isvs, type_isvs)
)
.optional()
.where(and(
  not(qi_rel_isvs.filter(state(StateEnum.INVALIDATED))),
  not(qi_isvs.filter(state(StateEnum.INVALIDATED)))
))

def matchAS = baseMatch.match(
  path()
    .node(qi_proj)
    .rel(qi_rel_as, RelationshipDirection.OUT, type_proj_rel_as)
    .node(qi_as, type_as)
)
.optional()
.where(and(
  not(qi_rel_as.filter(state(StateEnum.INVALIDATED))),
  not(qi_as.filter(state(StateEnum.INVALIDATED)))
))

def matchKS = baseMatch.match(
  path()
    .node(qi_proj)
    .rel(qi_rel_ks, RelationshipDirection.OUT, type_proj_rel_ks)
    .node(qi_ks, type_ks)
)
.optional()
.where(and(
  not(qi_rel_ks.filter(state(StateEnum.INVALIDATED))),
  not(qi_ks.filter(state(StateEnum.INVALIDATED)))
))

// Get the project list in-scope (once)
def qProjects = baseMatch.returns(
  prop("project_uuid", qi_proj.prop("\$cmdb_id")),
  prop("project_name", qi_proj.prop("Gen_Profil_nazov"))
)
def resProjects = Neo4j.execute(qProjects).data

// ----- parameter-driven narrowing to a single project -----
def wantUUID = (project_uuid ?: "").trim()
def wantName = (project_name ?: "").trim()

def chosen = null
if (wantUUID) {
  def needle = wantUUID.toLowerCase()
  for (row in resProjects) {
    def s = ("" + (row.project_uuid ?: "")).toLowerCase()
    if (s.contains(needle)) { chosen = row; break }
  }
}
if (chosen == null && wantName) {
  def needle = foldSk(wantName)
  for (row in resProjects) {
    def s = foldSk(row.project_name ?: "")
    if (s.contains(needle)) { chosen = row; break }
  }
}

def resProjectsScoped = (chosen != null) ? [chosen] : resProjects

// Pull endpoints per relation (stick to your matches) + include needed attrs
def qISVS = matchISVS.returns(
  prop("project_uuid",   qi_proj.prop("\$cmdb_id")),
  prop("endpoint_uuid",  qi_isvs.prop("\$cmdb_id")),
  prop("endpoint_name",  qi_isvs.prop("Gen_Profil_nazov")),
  prop("endpoint_meta",  qi_isvs.prop("Gen_Profil_kod_metais")),
  prop("isvs_modul",     qi_isvs.prop("EA_Profil_ISVS_modul_isvs"))
)
def qAS = matchAS.returns(
  prop("project_uuid",   qi_proj.prop("\$cmdb_id")),
  prop("endpoint_uuid",  qi_as.prop("\$cmdb_id")),
  prop("endpoint_name",  qi_as.prop("Gen_Profil_nazov")),
  prop("endpoint_meta",  qi_as.prop("Gen_Profil_kod_metais")),
  prop("as_ext",         qi_as.prop("EA_Profil_AS_dostupnost_pre_externu_integraciu"))
)
def qKS = matchKS.returns(
  prop("project_uuid",   qi_proj.prop("\$cmdb_id")),
  prop("endpoint_uuid",  qi_ks.prop("\$cmdb_id")),
  prop("endpoint_name",  qi_ks.prop("Gen_Profil_nazov")),
  prop("endpoint_meta",  qi_ks.prop("Gen_Profil_kod_metais"))
)

def resISVS = Neo4j.execute(qISVS).data
def resAS   = Neo4j.execute(qAS).data
def resKS   = Neo4j.execute(qKS).data

if ((wantUUID || wantName) && !chosen) {
  // Nothing matched → empty report
  return new Report(headers)
}

def projISVS = resISVS
def projAS   = resAS
def projKS   = resKS

if (chosen != null) {
  def pid = chosen.project_uuid

  def pISVS = new ArrayList()
  for (r in resISVS) if (r.project_uuid == pid) pISVS.add(r)
  projISVS = pISVS

  def pAS = new ArrayList()
  for (r in resAS) if (r.project_uuid == pid) pAS.add(r)
  projAS = pAS

  def pKS = new ArrayList()
  for (r in resKS) if (r.project_uuid == pid) pKS.add(r)
  projKS = pKS
}

// --- Helper to run a generic relation fetch and return Map<leftUUID, Set<rightUUID>> ---
def mapRel = { String relTypeName, boolean leftIsCentral, String leftTypeName, String rightTypeName ->
  def qiL = qi("L_${relTypeName}")
  def qiR = qi("R_${relTypeName}")
  def qiRel = qi("REL_${relTypeName}")
  def typeRel = type(relTypeName)
  def typeL = type(leftTypeName)
  def typeR = type(rightTypeName)

  // Direction depends on how the relation is modeled in Neo4j
  def p = path()
  if (leftIsCentral) {
    p = p.node(qiL, typeL).rel(qiRel, RelationshipDirection.OUT, typeRel).node(qiR, typeR)
  } else {
    p = p.node(qiL, typeL).rel(qiRel, RelationshipDirection.IN,  typeRel).node(qiR, typeR)
  }

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
  def m = new LinkedHashMap()  // String -> LinkedHashSet<String>
  for (r in rows) {
    def l  = (String) r.left_uuid
    def rr = (String) r.right_uuid
    if (l != null && rr != null) getSet(m, l).add(rr)
  }
  m
}

// Build the few relation maps we need (global, we’ll filter locally)
def AS_SLUZI_KS__AS_to_KS = mapRel("AS_sluzi_KS", true, "AS", "KS")
def AS_SLUZI_KS__KS_to_AS = new LinkedHashMap()
for (entry in AS_SLUZI_KS__AS_to_KS.entrySet()) {
  def asu  = (String) entry.key
  def kset = entry.value ?: []
  for (k in kset) getSet(AS_SLUZI_KS__KS_to_AS, (String) k).add(asu)
}

def KANAL_SPRISTUPNUJE_KS = mapRel("Kanal_spristupnuje_KS", true, "Kanal", "KS")
def KS_TO_KANAL = new LinkedHashMap()
for (entry in KANAL_SPRISTUPNUJE_KS.entrySet()) {
  def kanal = entry.key
  def kset = entry.value ?: []
  for (k in kset) getSet(KS_TO_KANAL, (String)k).add((String)kanal)
}

def ZS_ZOSKUPUJE_KS = mapRel("ZS_zoskupuje_KS", true, "ZS", "KS")
def KS_TO_ZS = new LinkedHashMap()
for (entry in ZS_ZOSKUPUJE_KS.entrySet()) {
  def zs = entry.key
  def kset = entry.value ?: []
  for (k in kset) getSet(KS_TO_ZS, (String)k).add((String)zs)
}

def KS_ASOCIUJE_AGENDA = mapRel("KS_asociuje_Agenda", true, "KS", "Agenda")  // KS → Agenda

def ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD = mapRel("ISVS_patri_pod_ISVS", true, "ISVS", "ISVS")
def ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT = new LinkedHashMap()
for (entry in ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD.entrySet()) {
  def parent = entry.key
  def children = entry.value ?: []
  for (child in children) getSet(ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT, (String)child).add((String)parent)
}

def INFRA_PREVADZKUJE_ISVS = mapRel("InfraSluzba_prevadzkuje_ISVS", true, "InfraSluzba", "ISVS")
def ISVS_TO_INFRA = new LinkedHashMap()
for (entry in INFRA_PREVADZKUJE_ISVS.entrySet()) {
  def infra = entry.key
  def setIsvs = entry.value ?: []
  for (i in setIsvs) getSet(ISVS_TO_INFRA, (String)i).add((String)infra)
}

def ISVS_REALIZUJE_AS__ISVS_TO_AS = mapRel("ISVS_realizuje_AS", true, "ISVS", "AS")
def AS_SLUZI_AS__AS_TO_AS = mapRel("AS_sluzi_AS", true, "AS", "AS")
def AS_SLUZI_AS__REV = new LinkedHashMap()
for (entry in AS_SLUZI_AS__AS_TO_AS.entrySet()) {
  def a = entry.key
  def outs = entry.value ?: []
  for (b in outs) getSet(AS_SLUZI_AS__REV, (String)b).add((String)a)
}

// ---- KS checks ----
def AS_BY_PROJECT_SEEN = new LinkedHashMap<String, Boolean>()  // captured by checkKS

def checkKS = { String ksUuid, String projUuid ->
  def errs = []
  def ASs = AS_SLUZI_KS__KS_to_AS[ksUuid]
  if (ASs == null || ASs.size() == 0) {
    errs << "KS nie je služené žiadnou AS"
  } else {
    def servedByThisProject = false
    for (a in (ASs ?: [])) {
      if (AS_BY_PROJECT_SEEN[(String)a]) { servedByThisProject = true; break }
    }
    if (!servedByThisProject) {
      errs << "KS nie je slúžené žiadnou AS realizovanou týmto projektom"
    }
  }
  if (!KS_TO_KANAL[ksUuid] || KS_TO_KANAL[ksUuid].size() == 0) {
    errs << "KS nie je sprístupnená žiadnym kanálom"
  }
  if (!KS_TO_ZS[ksUuid] || KS_TO_ZS[ksUuid].size() == 0) {
    errs << "KS nie je zoskupená žiadnou životnou situáciou"
  }
  if (!KS_ASOCIUJE_AGENDA[ksUuid] || KS_ASOCIUJE_AGENDA[ksUuid].size() == 0) {
    errs << "KS neasociuje žiadnu agendu"
  }
  errs
}

// ---- ISVS checks ----
def checkISVS = { String isvsUuid ->
  def errs = []
  def attrs = ATTR_CACHE[isvsUuid] ?: [:]
  def isModule = truthy(attrs.isvs_modul)

  def parents = ISVS_PATRI_POD_ISVS__CHILD_TO_PARENT[isvsUuid]
  def children = ISVS_PATRI_POD_ISVS__PARENT_TO_CHILD[isvsUuid]
  def infra = ISVS_TO_INFRA[isvsUuid]

  if (isModule && (parents == null || parents.size() == 0)) {
    errs << "ISVS je modul, ale nemá materský ISVS"
  }
  if (isModule && children != null && children.size() != 0) {
    for (c in children) {
      def (nm, mm, _) = nameMetaWithScope((String)c, "ISVS")
      errs << ("ISVS je modul, ale má dcérske ISVS: " + nm + " (" + mm + ")")
    }
  }
  if (!isModule && parents != null && parents.size() != 0) {
    for (p in parents) {
      def (nm, mm, _) = nameMetaWithScope((String)p, "ISVS")
      errs << ("ISVS nie je modul, ale patrí pod iné ISVS: " + nm + " (" + mm + ")")
    }
  }
  if (infra == null || infra.size() == 0) {
    errs << "ISVS nie je prevádzkovaná žiadnou infraštruktúrnou službou"
  }
  errs
}

// ---- AS checks ----
def checkAS = { String asUuid ->
  def errs = []

  def realized = false
  for (entry in ISVS_REALIZUJE_AS__ISVS_TO_AS.entrySet()) {
    def v = entry.value ?: []
    for (x in v) {
      if ((String)x == (String)asUuid) { realized = true; break }
    }
    if (realized) break
  }
  if (!realized) {
    errs << "AS nie je realizovaná žiadnym ISVS"
  }

  // AS (source) serves AS2 but AS lacks external integration flag
  def asAttrs = ATTR_CACHE[asUuid] ?: [:]
  def extEnabled = parseExtIntegrationFlag(asAttrs.as_ext)
  if (!extEnabled) {
    def asTargets = AS_SLUZI_AS__AS_TO_AS[asUuid] ?: []   // asUuid → AS2
    for (as2 in asTargets) {
      def (nm, mm, _) = nameMetaWithScope((String)as2, "AS")
      errs << ('AS nemá príznak "určená na externú integráciu", ale má vzťah na inú AS: ' + nm + " (" + mm + ")")
    }
  }

  // AS2 is source, but AS2 lacks external integration flag
  def sourcesToMe = AS_SLUZI_AS__REV[asUuid] ?: []        // AS2 → asUuid
  for (as2 in sourcesToMe) {
    def a2 = ATTR_CACHE[as2] ?: [:]
    def a2Ext = parseExtIntegrationFlag(a2.as_ext)
    if (!a2Ext) {
      def (nm, mm, _) = nameMetaWithScope((String)as2, "AS")
      errs << ('AS má vzťah na inú AS ktorá nemá príznak "určená na externú integráciu": ' + nm + " (" + mm + ")")
    }
  }

  errs
}

// ---- Fill ATTR_CACHE from our first-pass rows (no second query, no param/value) ----
for (r in resISVS) {
  def uuid = (String) r.endpoint_uuid
  if (uuid && ATTR_SEEN[(String)uuid] != true) {
    ATTR_CACHE[(String)uuid] = [
      uuid: uuid,
      name: (String) r.endpoint_name,
      meta: (String) r.endpoint_meta,
      isvs_modul: r.isvs_modul
    ]
    ATTR_SEEN[(String)uuid] = true
  }
}
for (r in resAS) {
  def uuid = (String) r.endpoint_uuid
  if (uuid && ATTR_SEEN[(String)uuid] != true) {
    ATTR_CACHE[(String)uuid] = [
      uuid: uuid,
      name: (String) r.endpoint_name,
      meta: (String) r.endpoint_meta,
      as_ext: r.as_ext
    ]
    ATTR_SEEN[(String)uuid] = true
  }
}
for (r in resKS) {
  def uuid = (String) r.endpoint_uuid
  if (uuid && ATTR_SEEN[(String)uuid] != true) {
    ATTR_CACHE[(String)uuid] = [
      uuid: uuid,
      name: (String) r.endpoint_name,
      meta: (String) r.endpoint_meta
      // KS doesn't need extra attrs for checks
    ]
    ATTR_SEEN[(String)uuid] = true
  }
}

def report = new Report(headers)

/*
def joinSemi = { lst ->
  if (lst == null) return ""
  def sb = new StringBuilder()
  for (int i = 0; i < lst.size(); i++) {
    if (i > 0) sb.append("; ")
    sb.append("" + lst[i])
  }
  sb.toString()
}*/

def joinSemi = { lst ->
  if (lst == null) return ""
  def sb = new StringBuilder()
  for (int i = 0; i < lst.size(); i++) {
    if (i > 0) sb.append("\n")
    sb.append("" + lst[i])
  }
  sb.toString()
}

for (p in resProjectsScoped) {
  def pid   = p.project_uuid
  def pname = p.project_name ?: ""

  // filter entries for this pid
  def entriesISVS = new ArrayList()
  for (r in projISVS) if (r.project_uuid == pid) entriesISVS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  def entriesAS = new ArrayList()
  for (r in projAS)   if (r.project_uuid == pid) entriesAS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  def entriesKS = new ArrayList()
  for (r in projKS)   if (r.project_uuid == pid) entriesKS.add([r.endpoint_uuid, r.endpoint_name, r.endpoint_meta])

  // build per-project AS set used by checkKS()
  AS_BY_PROJECT_SEEN.clear()
  for (row in entriesAS) {
    def asId = (String) row[0]
    if (asId != null) AS_BY_PROJECT_SEEN[asId] = true
  }

  boolean printedProject = false

  def blocks = [
    ["ISVS", entriesISVS],
    ["AS",   entriesAS],
    ["KS",   entriesKS],
  ]

  for (blk in blocks) {
    def label = blk[0]
    //def entries = blk[1] as List<List<String>> // cast trips SecureAST
    def entries = (blk[1] ?: [])

    if (entries.size() == 0) {
      report.add([
        duplicit_name_output ? pname : (printedProject ? "" : pname),
        label,
        (label == "ISVS" ? "(Projekt nerealizuje žiaden informačný systém verejnej správy)"
                        : label == "AS"   ? "(Projekt nerealizuje žiadnu aplikačnú službu)"
                                          : "(Projekt nerealizuje žiadnu koncovú službu)"),
        ""
      ])
      printedProject = true
      continue
    }

    // helper to add one component row, possibly with multiple error rows
    //def addComponentRows = { String projNameCell, String lbl, String compText, List errs -> // our neo4j hates typed casts
    def addComponentRows = { projNameCell, lbl, compText, errs ->
      if (errs == null || errs.size() == 0) {
        // single clean row (no errors)
        report.add([projNameCell, lbl, compText, ""])
      } else {
        // first error row with full context
        report.add([projNameCell, lbl, compText, errs[0]])
        // subsequent errors -> extra lines, only error column (keeps grouping pretty)
        for (int e = 1; e < errs.size(); e++) {
          report.add(["", "", "", errs[e]])
        }
      }
    }

    // First component line for this relation
    def (u0, n0, m0) = entries[0]
    def comp0 = displayComp(label, (String)u0, (String)n0, (String)m0)
    def err0 = (label == "KS")   ? checkKS(u0, pid)
              : (label == "ISVS")? checkISVS(u0)
                                : checkAS(u0)

    addComponentRows(duplicit_name_output ? pname : (printedProject ? "" : pname),
                    label, comp0, err0)
    printedProject = true

    // Remaining components
    for (int i = 1; i < entries.size(); i++) {
      def (uu, nn, mm) = entries[i]
      def comp = displayComp(label, (String)uu, (String)nn, (String)mm)
      def errs = (label == "KS")   ? checkKS(uu, pid)
                : (label == "ISVS")? checkISVS(uu)
                                  : checkAS(uu)

      addComponentRows(
        duplicit_name_output ? pname : "",
        duplicit_name_output ? label : "",
        comp,
        errs
      )
    }
  }
}

return report