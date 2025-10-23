// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov aplikačnej služby",      Header.Type.STRING),
  new Header("Kód MetaIS",                   Header.Type.STRING),
  new Header("Typ aplikačnej služby",        Header.Type.STRING),
  new Header("Správca",                      Header.Type.STRING)
]
// query identifiers for:
// - application service (qi_as) - main access pointer for AS
// - po (qi_po) - main access pointer for PO - used to access the name of Spravca that handles the end service
// - relation "po je spravca as" (qi_rel_spravca) - practically unused, except for node-node matching
def qi_as =                qi("as")
def qi_po =                qi("po")
def qi_rel_spravca =       qi("spravca")

// main nodes for AS and PO
def type_AS =              type("AS")
def type_PO =              type("PO")
// predicate tying PO to AS via "je spravca"
def type_PO_je_spravca_AS = type("PO_je_spravca_AS")

// this enum translates machine name (c_typ_as.3) to something human-readable ("originálne kompetencie vyšších územných celkov")
// enum for prop "EA_Profil_AS_typ_as"
def enumASType =           EnumsRepo.enumTypeMap("TYP_AS")

// here we tie our pointer (query identifier) qi_as to the actual node type_AS
// invalidated nodes are removed
def baseMatch = match(path().node(qi_as, type_AS)).where(not(qi_as.filter(state(StateEnum.INVALIDATED))))
// create a relation "je spravca" between AS and PO
// that way, whenever we access qi_po's props, it pulls out the correct PO who is the handler of the current AS
// we remove invalidated relations
def withSpravca = baseMatch.match(
    path()
    .node(qi_as)
    .rel(qi_rel_spravca, RelationshipDirection.IN, type_PO_je_spravca_AS)
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
    withSpravca.returns(count("totalCount", qi_as))
)

// main data harvest - take all the attributes that you need to construct the table
// see AS_raw and PO_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = withSpravca.returns(
    prop("name",          qi_as.prop("Gen_Profil_nazov")),
    prop("metais_code",   qi_as.prop("Gen_Profil_kod_metais")),
    prop("service_type",  qi_as.prop("EA_Profil_AS_typ_as")),
    prop("spravca_name",   qi_po.prop("Gen_Profil_nazov")),
).orderBy(qi_as.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

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
    def service_type = enumASType[row.service_type];

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.name, row.metais_code, service_type, row.spravca_name
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result