// === Columns ===
def headers = [
  new Header("KS_uuid", Header.Type.STRING),
  new Header("PO_uuid",  Header.Type.STRING)
]

// === QI & types ===
def qi_ks         = qi("ks")
def qi_rel_gestor = qi("gestor")
def qi_po         = qi("po")
def type_KS       = type("KS")
def type_PO       = type("PO")
def type_REL      = type("PO_je_gestor_KS")

// === Base KS ===
def base = match(path().node(qi_ks, type_KS))
           .where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))

// === Optional gestor relation ===
def q = base.match(
          path()
            .node(qi_ks)
            .rel(qi_rel_gestor, RelationshipDirection.IN, type_REL)
            .node(qi_po, type_PO)
        )
        .optional()
        .where(and(
          not(qi_rel_gestor.filter(state(StateEnum.INVALIDATED))),
          not(qi_po.filter(state(StateEnum.INVALIDATED)))
        ))

// === Pagination ===
def page    = (report.page ?: 1) as int
def perPage = (report.perPage ?: 500) as int

// === Total count of KS (for envelope) ===
def resCount = Neo4j.execute(q.returns(count("totalCount", qi_ks)))
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0

// === Return only the two props (lightweight) ===
def query = q.returns(
  prop("service_name", qi_ks.prop("\$cmdb_id")),
  prop("gestor_name",  qi_po.prop("\$cmdb_id"))
)
.orderBy(qi_ks.prop("Gen_Profil_nazov"), OrderDirection.ASC)
.limit(perPage)
.offset((page - 1) * perPage)

def res = Neo4j.execute(query)

// === Build table ===
def table = new Report(headers)
for (row in res.data) {
  table.add([ row.service_name, row.gestor_name ])  // gestor_name may be null if none
}

def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
return result