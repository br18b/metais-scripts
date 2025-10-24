// we will need to format the date, here's a function to do it
def dateFormat = new SimpleDateFormat("dd.MM.yyyy")
def formatDate = { Long millis ->
    return millis != null ? dateFormat.format(new Date(millis)) : null
}

// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov",            Header.Type.STRING),
  new Header("Kód MetaIS",       Header.Type.STRING),
  new Header("Stav KRIT",        Header.Type.STRING),
  new Header("Zodpovedný orgán", Header.Type.STRING),
  new Header("Posledná zmena",   Header.Type.DATE)
]

// query identifiers for:
// - IT development strategy (qi_krit) - main access pointer for KRIT
// - po (qi_po) - main access pointer for PO - used to access the name of responsible governing authority
// - relation "po predklada KRIT" (qi_rel_organ) - practically unused, except for node-node matching
def qi_krit =      qi("krit")
def qi_po =        qi("po")
def qi_rel_organ = qi("zodpov")

// main nodes for KRIT and PO
def type_KRIT = type("KRIS")
def type_PO =   type("PO")
// predicate tying PO to KRIT via "predklada"
def type_PO_predklada_KRIT = type("PO_predklada_KRIS")

// enum for prop "EA_Profil_KS_typ_ks" - translates machine name (c_stav_kris.4) to something human-readable ("vrátený na dopracovanie")
def enumKRITStatus = EnumsRepo.enumTypeMap("STAV_KRIS")

// here we tie our pointer (query identifier) qi_krit to the actual node type_KRIT
// invalidated nodes are removed
def baseMatch = match(path().node(qi_krit, type_KRIT)).where(not(qi_krit.filter(state(StateEnum.INVALIDATED))))
// here we create a relation "predklada" between KRIT and PO
// that way, whenever we access qi_po's props, it pulls out the correct PO who is the responsible governing authority for the given KRIT
// we remove invalidated relations
def withAuth = baseMatch.match(
    path()
    .node(qi_krit)
    .rel(qi_rel_organ, RelationshipDirection.IN, type_PO_predklada_KRIT)
    .node(qi_po, type_PO)
)
.optional()
.where(and(
    not(qi_rel_organ.filter(state(StateEnum.INVALIDATED))),
    not(qi_po.filter(state(StateEnum.INVALIDATED)))
))

// pagination. Undefined (null) page and perPage is sorted out below
def page =    report.page
def perPage = report.perPage

// json metadata
def resCount = Neo4j.execute(
    withAuth.returns(count("totalCount", qi_krit))
)

// main data harvest - take all the attributes that you need to construct the table
// see KRIT_raw and PO_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = withAuth.returns(
    prop("name",        qi_krit.prop("Gen_Profil_nazov")),
    prop("metais_code", qi_krit.prop("Gen_Profil_kod_metais")),
    prop("status",      qi_krit.prop("Profil_KRIS_stav_kris")),
    prop("auth_name",   qi_po.prop("Gen_Profil_nazov")),
    prop("last_modif",  qi_krit.prop("\$cmdb_lastModifiedAt")),
)

// pagination - only if page and perPage are not null these are applied
if (perPage && perPage > 0) {
  query = query.limit(perPage)
}
if (page && page > 0) {
  query = query.offset((page - 1) * perPage)
}

// now we pull the data defined in query
def res = Neo4j.execute(query)

// to manipulate the individual entries, we create a Report of the shape of the headers defined in the beginning
def table = new Report(headers)

// traverse the data and create the table row-by-row
for (row in res.data) {
    // convert the machine codes to human-readable using enums
    def status = enumKRITStatus[row.status];
    def last_modif = formatDate(row.last_modif as Long)

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.name, row.metais_code, status, row.auth_name, last_modif
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result