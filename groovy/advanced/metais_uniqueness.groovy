// ============================================================================
//
// Report: Duplicate MetaIS codes across selected types + Associated PO
// Types: KS / AS / ZS / ISVS / Projekt
// Data: MetaIS / Neo4j
//
// PARAMETERS (boolean-ish; "1","true","yes","áno","t"...):
//   - parameters.inclKS
//   - parameters.inclAS
//   - parameters.inclZS
//   - parameters.inclISVS
//   - parameters.inclProjekt
// Optional:
//   - parameters.groupByType            (truthy → duplicates counted within the same type only)
//   - parameters.ignoreTrivialMatches   (truthy → HIDE trivial dupes where NAME and PO match across all instances)
//
// OUTPUT COLUMNS (order):
//   - MetaIS kód
//   - Názov
//   - Typ
//   - Asociovaná povinná osoba
//
// ============================================================================

// ---------- Truthiness + helpers ---------------------------------------------
def TRUTHY_SET = [:]; for (tok in ["1","true","yes","y","áno","ano","t"]) TRUTHY_SET[(String)tok] = true
def truthy = { x -> TRUTHY_SET[(String)("" + (x ?: "")).trim().toLowerCase()] == true }

def normalize = { v ->
  if (v == null) return ""
  ("" + v).trim().toLowerCase()
}

def normalizeMeta = { v ->
  def s = normalize(v)
  s == "" ? null : s
}

def getSet = { Map m, String k ->
  def s = (LinkedHashSet) m[k]
  if (s == null) { s = new LinkedHashSet(); m[k] = s }
  (LinkedHashSet<String>) s
}

// ---------- Headers -----------------------------------------------------------
def headers = [
  new Header("MetaIS kód",               Header.Type.STRING),
  new Header("Názov",                    Header.Type.STRING),
  new Header("Typ",                      Header.Type.STRING),
  new Header("Asociovaná povinná osoba", Header.Type.STRING),
  new Header("Kód MetaIS (PO)",          Header.Type.STRING),
  new Header("Duplicitné záznamy",       Header.Type.STRING)
]

// ---------- Parameters --------------------------------------------------------
final boolean inclKS      = truthy(report.parameters?.inclKS)
final boolean inclAS      = truthy(report.parameters?.inclAS)
final boolean inclZS      = truthy(report.parameters?.inclZS)
final boolean inclISVS    = truthy(report.parameters?.inclISVS)
final boolean inclProjekt = truthy(report.parameters?.inclProjekt)
final boolean inclPO      = truthy(report.parameters?.inclPO)

final boolean groupByType          = truthy(report.parameters?.groupByType)
final boolean ignoreTrivialMatches = truthy(report.parameters?.ignoreTrivialMatches)

if (!inclKS && !inclAS && !inclZS && !inclISVS && !inclProjekt && !inclPO) {
  def r = new Report(headers)
  r.add(["Vyber aspoň jeden typ (KS/AS/ZS/ISVS/Projekt/PO)", "", "", "", "", ""])
  return new ReportResult("TABLE", r, 1)
}

// ---------- Per-type lean fetch (uuid, name, meta) ----------------------------
def fetchType = { String typeName ->
  def qx = qi("n_" + typeName)
  def q = match(path().node(qx, type(typeName)))
    .where(not(qx.filter(state(StateEnum.INVALIDATED))))
    .returns(
      prop("uuid", qx.prop("\$cmdb_id")),
      prop("name", qx.prop("Gen_Profil_nazov")),
      prop("meta", qx.prop("Gen_Profil_kod_metais"))
    )
  Neo4j.execute(q).data?.collect { row ->
    [
      uuid : (String) row.uuid,
      name : (String) row.name,
      meta : (String) row.meta,
      type : typeName
    ]
  } ?: []
}

// ---------- Collect rows only for selected types ------------------------------
def rows = new ArrayList<Map<String,String>>()
if (inclKS)      rows.addAll(fetchType("KS"))
if (inclAS)      rows.addAll(fetchType("AS"))
if (inclZS)      rows.addAll(fetchType("ZS"))
if (inclISVS)    rows.addAll(fetchType("ISVS"))
if (inclProjekt) rows.addAll(fetchType("Projekt"))
if (inclPO)      rows.addAll(fetchType("PO"))

if (rows.isEmpty()) return new Report(headers)

// ---------- PO→Target relation maps (to names) --------------------------------
// Each function returns: Map<TARGET_UUID, Set<PO_NAME>>
def mapPONameToTarget = { String relTypeName, String rightTypeName ->
  def qiPO  = qi("po_" + relTypeName)
  def qiRel = qi("rel_" + relTypeName)
  def qiRT  = qi("rt_" + relTypeName)

  def q = match(
      path()
        .node(qiPO,  type("PO"))
        .rel(qiRel,  RelationshipDirection.OUT, type(relTypeName))
        .node(qiRT,  type(rightTypeName))
    )
    .where(and(
      not(qiRel.filter(state(StateEnum.INVALIDATED))),
      not(qiPO.filter(state(StateEnum.INVALIDATED))),
      not(qiRT.filter(state(StateEnum.INVALIDATED)))
    ))
    .returns(
      prop("po_name", qiPO.prop("Gen_Profil_nazov")),
      prop("po_meta", qiPO.prop("Gen_Profil_kod_metais")),
      prop("rt_uuid", qiRT.prop("\$cmdb_id"))
    )

  def out = new LinkedHashMap<String, LinkedHashSet<String>>()
  def data = Neo4j.execute(q).data ?: []
  for (row in data) {
    def poName = (String) row.po_name
    def tgt    = (String) row.rt_uuid
    if (tgt) getSet(out, tgt).add(poName ?: "")
  }
  out
}

def mapPOUuidToTarget = { String relTypeName, String rightTypeName ->
  def qiPO  = qi("poU_" + relTypeName)
  def qiRel = qi("relU_" + relTypeName)
  def qiRT  = qi("rtU_"  + relTypeName)

  def q = match(
      path()
        .node(qiPO,  type("PO"))
        .rel(qiRel,  RelationshipDirection.OUT, type(relTypeName))
        .node(qiRT,  type(rightTypeName))
    )
    .where(and(
      not(qiRel.filter(state(StateEnum.INVALIDATED))),
      not(qiPO.filter(state(StateEnum.INVALIDATED))),
      not(qiRT.filter(state(StateEnum.INVALIDATED)))
    ))
    .returns(
      prop("po_uuid", qiPO.prop("\$cmdb_id")),
      prop("rt_uuid", qiRT.prop("\$cmdb_id"))
    )

  def out = new LinkedHashMap<String, LinkedHashSet<String>>()
  def data = Neo4j.execute(q).data ?: []
  for (row in data) {
    def poUuid = (String) row.po_uuid
    def tgt    = (String) row.rt_uuid
    if (tgt) getSet(out, tgt).add(poUuid ?: "")
  }
  out
}

def mapPOMetaToTarget = { String relTypeName, String rightTypeName ->
  def qiPO  = qi("poM_" + relTypeName)
  def qiRel = qi("relM_" + relTypeName)
  def qiRT  = qi("rtM_"  + relTypeName)

  def q = match(
      path()
        .node(qiPO,  type("PO"))
        .rel(qiRel,  RelationshipDirection.OUT, type(relTypeName))
        .node(qiRT,  type(rightTypeName))
    )
    .where(and(
      not(qiRel.filter(state(StateEnum.INVALIDATED))),
      not(qiPO.filter(state(StateEnum.INVALIDATED))),
      not(qiRT.filter(state(StateEnum.INVALIDATED)))
    ))
    .returns(
      prop("po_meta", qiPO.prop("Gen_Profil_kod_metais")),
      prop("rt_uuid", qiRT.prop("\$cmdb_id"))
    )

  def out = new LinkedHashMap<String, LinkedHashSet<String>>()
  def data = Neo4j.execute(q).data ?: []
  for (row in data) {
    def poMeta = (String) row.po_meta
    def tgt    = (String) row.rt_uuid
    if (tgt) getSet(out, tgt).add(poMeta ?: "")
  }
  out
}

// Build once per run (only if that type is included)
def KS_TO_PO_UUID    = inclKS      ? mapPOUuidToTarget("PO_je_gestor_KS",     "KS")      : [:]
def ISVS_TO_PO_UUID  = inclISVS    ? mapPOUuidToTarget("PO_je_spravca_ISVS",  "ISVS")    : [:]
def PROJ_TO_PO_UUID  = inclProjekt ? mapPOUuidToTarget("PO_asociuje_Projekt", "Projekt") : [:]
def AS_TO_PO_UUID    = inclAS      ? mapPOUuidToTarget("PO_je_spravca_AS",    "AS")      : [:]

def KS_TO_PO_GESTOR    = inclKS      ? mapPONameToTarget("PO_je_gestor_KS",      "KS")      : [:]
def ISVS_TO_PO_SPRAVCA = inclISVS    ? mapPONameToTarget("PO_je_spravca_ISVS",   "ISVS")    : [:]
def PROJ_TO_PO_ASSOC   = inclProjekt ? mapPONameToTarget("PO_asociuje_Projekt",  "Projekt") : [:]
def AS_TO_PO_SPRAVCA   = inclAS      ? mapPONameToTarget("PO_je_spravca_AS",     "AS")      : [:]

def KS_TO_PO_META    = inclKS      ? mapPOMetaToTarget("PO_je_gestor_KS",     "KS")      : [:]
def ISVS_TO_PO_META  = inclISVS    ? mapPOMetaToTarget("PO_je_spravca_ISVS",  "ISVS")    : [:]
def PROJ_TO_PO_META  = inclProjekt ? mapPOMetaToTarget("PO_asociuje_Projekt", "Projekt") : [:]
def AS_TO_PO_META    = inclAS      ? mapPOMetaToTarget("PO_je_spravca_AS",    "AS")      : [:]
// ZS: nothing

def poUuidSignature = { String typ, String uuid ->
  if (!uuid) return ""
  def raw = null
  if (typ == "KS")       raw = (KS_TO_PO_UUID[uuid]    ?: new LinkedHashSet())
  else if (typ == "ISVS") raw = (ISVS_TO_PO_UUID[uuid]  ?: new LinkedHashSet())
  else if (typ == "Projekt") raw = (PROJ_TO_PO_UUID[uuid] ?: new LinkedHashSet())
  else if (typ == "AS")  raw = (AS_TO_PO_UUID[uuid]    ?: new LinkedHashSet())
  if (raw == null || raw.isEmpty()) return ""   // treat as empty uuid signature

  // Build ordered canonical list without using Collections.sort
  def arr = []
  for (v in raw) { if ((v ?: "") != "") arr << (String)v }
  arr.sort { a, b -> a <=> b }   // Groovy list sort
  return arr.join(";")
}

def poFor = { String typ, String uuid ->
  if (!uuid) return ""
  if (typ == "KS")      return (KS_TO_PO_GESTOR[uuid]    ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "ISVS")    return (ISVS_TO_PO_SPRAVCA[uuid] ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "Projekt") return (PROJ_TO_PO_ASSOC[uuid]   ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "AS")      return (AS_TO_PO_SPRAVCA[uuid]   ?: [])?.findAll{ it }?.join("; ") ?: ""
  return "" // ZS or anything else
}

def poMetaFor = { String typ, String uuid ->
  if (!uuid) return ""
  if (typ == "KS")      return (KS_TO_PO_META[uuid]    ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "ISVS")    return (ISVS_TO_PO_META[uuid]  ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "Projekt") return (PROJ_TO_PO_META[uuid]  ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "AS")      return (AS_TO_PO_META[uuid]    ?: [])?.findAll{ it }?.join("; ") ?: ""
  return "" // ZS or anything else
}

// ---------- Bucket by normalized MetaIS code (and optionally type) -----------
def buckets = new LinkedHashMap<Object, List<Map>>()
def keyFn = { Map rec ->
  def m = normalizeMeta(rec.meta)
  if (m == null) return null
  groupByType ? (Object) [rec.type, m] : (Object) m
}

for (rec in rows) {
  def key = keyFn(rec)
  if (key == null) continue
  def lst = (List<Map>) buckets[key]
  if (lst == null) { lst = new ArrayList<Map>(); buckets[key] = lst }
  lst.add(rec)
}

// ---------- Build duplicate groups -------------------------------------------
def groups = new ArrayList<Map>()
for (entry in buckets.entrySet()) {
  def list = (List<Map>) entry.value
  if (list == null || list.size() <= 1) continue
  // Prefer first non-empty original meta for display (preserve original casing)
  def displayMeta = (String)(list.find { (it.meta ?: "").trim() != "" }?.meta ?: "<bez kódu>")
  groups.add([ key: entry.key, meta: displayMeta, count: list.size(), items: list ])
}

def report = new Report(headers)
if (groups.isEmpty()) {
  report.add(["(Žiadne problematické duplicitné kódy po odfiltrovaní triviálnych zhod)", "", "", "", "", ""])
  return new ReportResult("TABLE", report, 1)
}

// ---------- OPTIONAL: hide trivial button-spam duplicates --------------------
// If enabled, drop groups where ALL instances share identical (name AND PO)
if (ignoreTrivialMatches) {
  // For each duplicate-code group, collapse identical rows by PO-UUID signature
  for (int gi = 0; gi < groups.size(); gi++) {
    def g = groups[gi]
    def parts = new LinkedHashMap<String, List<Map>>()   // signature -> list of recs
    for (rec in (List<Map>) g.items) {
      def sig = poUuidSignature((String)rec.type, (String)rec.uuid)  // "" if no PO
      def lst = parts[sig]
      if (lst == null) { lst = new ArrayList<Map>(); parts[sig] = lst }
      lst.add(rec)
    }
    // Rebuild items: keep first per signature, carry a collapsed count
    def compact = new ArrayList<Map>()
    for (entry in parts.entrySet()) {
      def lst = (List<Map>) entry.value
      def first = lst[0]
      first._collapsed = Math.max(0, lst.size() - 1)  // store count on the kept row
      compact.add(first)
    }
    // Replace group's items with compacted list
    g.items = compact
    // Update count to reflect displayed rows (optional)
    g.count_display = compact.size()
  }
}

// After filtering, if none remain, say so.
if (groups.isEmpty()) {
  report.add(["(Žiadne problematické duplicitné kódy po odfiltrovaní triviálnych zhod)", "", "", "", ""])
  return new ReportResult("TABLE", report, 1)
}

// ---------- Sort groups and render -------------------------------------------
// Sort by: descending count, then MetaIS code, then type
groups.sort { a, b ->
  def c = ((int)b.count) <=> ((int)a.count)
  if (c != 0) return c
  def ma = (String)(a.meta ?: ""); def mb = (String)(b.meta ?: "")
  c = ma.toLowerCase() <=> mb.toLowerCase()
  if (c != 0) return c
  def ta = (String)((a.items?.getAt(0)?.type) ?: "")
  def tb = (String)((b.items?.getAt(0)?.type) ?: "")
  return ta <=> tb
}

int totalRows = 0
for (g in groups) {
  // Sort instances within group by type, then name for stable output
  def items = (List<Map>) g.items
  items.sort { x, y ->
    def t = ((String)(x.type ?: "")) <=> ((String)(y.type ?: ""))
    if (t != 0) return t
    return ((String)(x.name ?: "")) <=> ((String)(y.name ?: ""))
  }

  boolean printedMeta = false
  for (rec in items) {
    def metaCell     = printedMeta ? "" : g.meta
    def assocPO      = poFor((String)rec.type, (String)rec.uuid)
    def assocPOMeta  = poMetaFor((String)rec.type, (String)rec.uuid)
    def collapsedTxt = (rec._collapsed instanceof Integer && rec._collapsed > 0)
                       ? ("" + rec._collapsed)   // or: "zlučené " + rec._collapsed
                       : ""

    report.add([
      metaCell,
      (String) (rec.name ?: ""),
      (String) (rec.type ?: ""),
      assocPO,
      assocPOMeta,
      collapsedTxt
    ])
    totalRows++
    printedMeta = true
  }
}

return new ReportResult("TABLE", report, totalRows)