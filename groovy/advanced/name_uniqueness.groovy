// ============================================================================
// Report: Duplicate Names across selected types + Associated PO
// Types: KS / AS / ZS / ISVS / Projekt
// Data: MetaIS / Neo4j
//
// PARAMETERS (boolean-ish; "1", "true", "yes", "áno", ...):
//   - parameters.inclKS
//   - parameters.inclAS
//   - parameters.inclZS
//   - parameters.inclISVS
//   - parameters.inclProjekt
// Optional:
//   - parameters.groupByType  (truthy → dupes counted within same type only)
//
// OUTPUT COLUMNS
//   - Názov
//   - Typ
//   - MetaIS kód
//   - Asociovaná povinná osoba
// ============================================================================

// ---------- Truthiness + helpers ---------------------------------------------
def TRUTHY_SET = [:]; for (tok in ["1","true","yes","y","áno","ano","t"]) TRUTHY_SET[(String)tok] = true
def truthy = { x -> TRUTHY_SET[(String)("" + (x ?: "")).trim().toLowerCase()] == true }

def normalizeName = { v ->
  if (v == null) return null
  def s = ("" + v).trim()
  if (s == "") return null
  s.toLowerCase()
}

def getSet = { Map m, String k ->
  def s = (LinkedHashSet) m[k]
  if (s == null) { s = new LinkedHashSet(); m[k] = s }
  (LinkedHashSet<String>) s
}

// ---------- Headers -----------------------------------------------------------
def headers = [
  new Header("Názov",                     Header.Type.STRING),
  new Header("Typ",                       Header.Type.STRING),
  new Header("Kod MetaIS",                Header.Type.STRING),
  new Header("Asociovaná povinná osoba",  Header.Type.STRING)
]

// ---------- Parameters --------------------------------------------------------
final boolean inclKS      = truthy(report.parameters?.inclKS)
final boolean inclAS      = truthy(report.parameters?.inclAS)
final boolean inclZS      = truthy(report.parameters?.inclZS)
final boolean inclISVS    = truthy(report.parameters?.inclISVS)
final boolean inclProjekt = truthy(report.parameters?.inclProjekt)
final boolean inclPO      = truthy(report.parameters?.inclPO)

final boolean groupByType = truthy(report.parameters?.groupByType)

if (!inclKS && !inclAS && !inclZS && !inclISVS && !inclProjekt && !inclPO) {
  def r = new Report(headers)
  r.add(["Vyber aspoň jeden typ (KS/AS/ZS/ISVS/Projekt/PO)", "", "", ""])
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
      prop("po_name",  qiPO.prop("Gen_Profil_nazov")),
      prop("rt_uuid",  qiRT.prop("\$cmdb_id"))
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

// Build once per run (used conditionally when those types are included)
def KS_TO_PO_GESTOR    = inclKS      ? mapPONameToTarget("PO_je_gestor_KS",      "KS")      : [:]
def ISVS_TO_PO_SPRAVCA = inclISVS    ? mapPONameToTarget("PO_je_spravca_ISVS",   "ISVS")    : [:]
def PROJ_TO_PO_ASSOC   = inclProjekt ? mapPONameToTarget("PO_asociuje_Projekt",  "Projekt") : [:]
def AS_TO_PO_SPRAVCA   = inclAS      ? mapPONameToTarget("PO_je_spravca_AS",     "AS")      : [:]
// ZS: nothing

def poFor = { String typ, String uuid ->
  if (!uuid) return ""
  if (typ == "KS")      return (KS_TO_PO_GESTOR[uuid]    ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "ISVS")    return (ISVS_TO_PO_SPRAVCA[uuid] ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "Projekt") return (PROJ_TO_PO_ASSOC[uuid]   ?: [])?.findAll{ it }?.join("; ") ?: ""
  if (typ == "AS")      return (AS_TO_PO_SPRAVCA[uuid]   ?: [])?.findAll{ it }?.join("; ") ?: ""
  return "" // ZS or anything else
}

// ---------- Bucket by normalized name (and optionally type) -------------------
def buckets = new LinkedHashMap<Object, List<Map>>()
def keyFn = { Map rec ->
  def n = normalizeName(rec.name)
  if (n == null) return null
  groupByType ? (Object) [rec.type, n] : (Object) n
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
  def displayName = (String)(list.find { (it.name ?: "").trim() != "" }?.name ?: "<bez názvu>")
  groups.add([ key: entry.key, name: displayName, count: list.size(), items: list ])
}

def report = new Report(headers)
if (groups.isEmpty()) {
  report.add(["(Žiadne duplicitné názvy v zvolenom rozsahu)", "", "", ""])
  return new ReportResult("TABLE", report, 1)
}

// ---------- Sort groups and render -------------------------------------------
groups.sort { a, b ->
  def c = ((int)b.count) <=> ((int)a.count)
  if (c != 0) return c
  def na = (String)(a.name ?: ""); def nb = (String)(b.name ?: "")
  c = na.toLowerCase() <=> nb.toLowerCase()
  if (c != 0) return c
  def ta = (String)((a.items?.getAt(0)?.type) ?: "")
  def tb = (String)((b.items?.getAt(0)?.type) ?: "")
  return ta <=> tb
}

int totalRows = 0
for (g in groups) {
  def items = (List<Map>) g.items
  items.sort { x, y ->
    def t = ((String)(x.type ?: "")) <=> ((String)(y.type ?: ""))
    if (t != 0) return t
    return ((String)(x.meta ?: "")) <=> ((String)(y.meta ?: ""))
  }

  boolean printedName = false
  for (rec in items) {
    def nameCell = printedName ? "" : g.name
    def assocPO  = poFor((String)rec.type, (String)rec.uuid)
    report.add([
      nameCell,
      (String) (rec.type ?: ""),
      (String) (rec.meta ?: ""),
      assocPO
    ])
    totalRows++
    printedName = true
  }
}

return new ReportResult("TABLE", report, totalRows)