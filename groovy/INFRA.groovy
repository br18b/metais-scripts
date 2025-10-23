// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov infraštruktúrnej služby", Header.Type.STRING),
  new Header("Kód MetaIS",                    Header.Type.STRING),
  new Header("Typ cloudovej služby",          Header.Type.STRING)
]
// query identifier for infrastructure service (qi_ifs) - main access pointer for InfraSluzba
def qi_ifs = qi("ifs")

// main node for InfraSluzba
def type_IFS = type("InfraSluzba")

// this enum translates machine name (c_typ_cloud_sluzba_is.1) to something human-readable ("Žiadny")
// enum for prop "TYP_CLOUD_SLUZBA_IS"
def enumIFSType = EnumsRepo.enumTypeMap("TYP_CLOUD_SLUZBA_IS")

// here we tie our pointer (query identifier) qi_ifs to the actual node type_IFS
// invalidated nodes are removed
def baseMatch = match(path().node(qi_ifs, type_IFS)).where(not(qi_ifs.filter(state(StateEnum.INVALIDATED))))

// pagination. Undefined (null) page and perPage is sorted out below
def page =    report.page
def perPage = report.perPage

// json metadata
def resCount = Neo4j.execute(
    baseMatch.returns(count("totalCount", qi_ifs))
)

// main data harvest - take all the attributes that you need to construct the table
// see AS_raw and PO_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = baseMatch.returns(
    prop("name",          qi_ifs.prop("Gen_Profil_nazov")),
    prop("metais_code",   qi_ifs.prop("Gen_Profil_kod_metais")),
    prop("service_type",  qi_ifs.prop("EA_Profil_InfraSluzba_typ_cloudovej_sluzby"))
).orderBy(qi_ifs.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

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
    def service_type = (row.service_type != null) ? enumIFSType[row.service_type] : enumIFSType["c_typ_cloud_sluzba_is.1"];

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.name, row.metais_code, service_type
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result