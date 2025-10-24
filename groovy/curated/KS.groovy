// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov koncovej služby",        Header.Type.STRING),
  new Header("Kód MetaIS",                   Header.Type.STRING),
  new Header("Typ služby",                   Header.Type.STRING),
  new Header("Úroveň elektronizácie služby", Header.Type.STRING),
  new Header("Gestor služby",                Header.Type.STRING)
]

// query identifiers for:
// - end service (qi_ks) - main access pointer for KS
// - po (qi_po) - main access pointer for PO - used to access the name of Gestor that gestors the end service
// - relation "po je gestor ks" (qi_rel_gestor) - practically unused, except for node-node matching
def qi_ks =                qi("ks")
def qi_po =                qi("po")
def qi_rel_gestor =        qi("gestor")

// main nodes for KS and PO
def type_KS =              type("KS")
def type_PO =              type("PO")
// predicate tying PO to KS via "je gestor"
def type_PO_je_gestor_KS = type("PO_je_gestor_KS")

// these enums translate machine name (c_typ_ks.5) to something human-readable ("prenesený výkon štátnej správy na iné osoby ako územnú samosprávu")
// enum for prop "EA_Profil_KS_typ_ks"
def enumKSType =           EnumsRepo.enumTypeMap("TYP_KS")
// enum for "EA_Profil_KS_sofistikovanost" - translates stuff like "c_sofistikovanost.1" to "úroveň 0"
// note: simple enums like these can be hard-coded but more pairs can be added and sometimes keys are not consistent, i.e.
// PRISTUPOVE_MIESTO: "c_pristup.1": "Ústredné kontaktné centrum" but then: "c_pristupove_miesto.8": "sms"
// key takeaway: if anything looks machine-readable but makes no sense to you, find a map (enum) that takes care of it
def enumLevel =            EnumsRepo.enumTypeMap("SOFISTIKOVANOST")

// here we tie our pointer (query identifier) qi_ks to the actual node type_KS
// invalidated nodes are removed
def baseMatch = match(path().node(qi_ks, type_KS)).where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))
// here we create a relation "je gestor" between KS and PO
// that way, whenever we access qi_po's props, it pulls out the correct PO who is the gestor to the given KS
// we remove invalidated relations
def withGestor = baseMatch.match(
    path()
    .node(qi_ks)
    .rel(qi_rel_gestor, RelationshipDirection.IN, type_PO_je_gestor_KS)
    .node(qi_po, type_PO)
)
.optional()
.where(and(
    not(qi_rel_gestor.filter(state(StateEnum.INVALIDATED))),
    not(qi_po.filter(state(StateEnum.INVALIDATED)))
))

// pagination. Undefined (null) page and perPage is sorted out below
def page =    report.page
def perPage = report.perPage

// json metadata
def resCount = Neo4j.execute(
    withGestor.returns(count("totalCount", qi_ks))
)

// main data harvest - take all the attributes that you need to construct the table
// see KS_raw and PO_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = withGestor.returns(
    prop("service_name",          qi_ks.prop("Gen_Profil_nazov")),
    prop("metais_code",           qi_ks.prop("Gen_Profil_kod_metais")),
    prop("service_type",          qi_ks.prop("EA_Profil_KS_typ_ks")),
    prop("e_level",               qi_ks.prop("EA_Profil_KS_sofistikovanost")),
    prop("gestor_name",           qi_po.prop("Gen_Profil_nazov")),
).orderBy(qi_ks.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

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
    def service_type = enumKSType[row.service_type];
    def e_level = enumLevel[row.e_level];

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.service_name, row.metais_code, service_type, e_level, row.gestor_name
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result