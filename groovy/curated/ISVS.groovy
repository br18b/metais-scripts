// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov informačného systému", Header.Type.STRING),
  new Header("Kód MetaIS",                 Header.Type.STRING),
  new Header("Typ ISVS",                   Header.Type.STRING),
  new Header("Stav ISVS",                  Header.Type.STRING),
  new Header("Správca",                    Header.Type.STRING)
]

// query identifiers for:
// - information system (qi_isvs) - main access pointer for ISVS
// - po (qi_po) - main access pointer for PO - used to access the name of Spravca that handles the information system
// - relation "po je sprava isvs" (qi_rel_spravca) - practically unused, except for node-node matching
def qi_isvs =        qi("isvs")
def qi_po =          qi("po")
def qi_rel_spravca = qi("spravca")

// main nodes for ISVS and PO
def type_ISVS =               type("ISVS")
def type_PO =                 type("PO")
// predicate tying PO to ISVS via "je spravca"
def type_PO_je_spravca_ISVS = type("PO_je_spravca_ISVS")

// these enums translate machine name (c_typ_isvs.5) to something human-readable ("Ekonomický a administratívny chod")
// enum for prop "EA_Profil_ISVS_typ_isvs"
def enumISVSType = EnumsRepo.enumTypeMap("TYP_ISVS")
// enum for prop "EA_Profil_ISVS_stav_isvs"
def enumISVState = EnumsRepo.enumTypeMap("STAV_ISVS")

// here we tie our pointer (query identifier) qi_isvs to the actual node type_ISVS
// invalidated nodes are removed
def baseMatch = match(path().node(qi_isvs, type_ISVS)).where(not(qi_isvs.filter(state(StateEnum.INVALIDATED))))
// here we create a relation "je spravca" between ISVS and PO
// that way, whenever we access qi_po's props, it pulls out the correct PO who is the handler of the given ISVS
// we remove invalidated relations
def withSpravca = baseMatch.match(
    path()
    .node(qi_isvs)
    .rel(qi_rel_spravca, RelationshipDirection.IN, type_PO_je_spravca_ISVS)
    .node(qi_po, type_PO)
)
.optional()
.where(and(
    not(qi_rel_spravca.filter(state(StateEnum.INVALIDATED))),
    not(qi_po.filter(state(StateEnum.INVALIDATED)))
))

// pagination. Undefined (null) page and perPage is sorted out below
def page =    report.page
def perPage = report.perPage

// json metadata
def resCount = Neo4j.execute(
    withSpravca.returns(count("totalCount", qi_isvs))
)

// main data harvest - take all the attributes that you need to construct the table
// see ISVS_raw and PO_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = withSpravca.returns(
    prop("isvs_name",          qi_isvs.prop("Gen_Profil_nazov")),
    prop("metais_code",        qi_isvs.prop("Gen_Profil_kod_metais")),
    prop("isvs_type",          qi_isvs.prop("EA_Profil_ISVS_typ_isvs")),
    prop("isvs_state",         qi_isvs.prop("EA_Profil_ISVS_stav_isvs")),
    prop("spravca_name",       qi_po.prop("Gen_Profil_nazov")),
).orderBy(qi_isvs.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

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
    def isvs_type = enumISVSType[row.isvs_type];
    def isvs_state = enumISVState[row.isvs_state];

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.isvs_name, row.metais_code, isvs_type, isvs_state, row.spravca_name
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result