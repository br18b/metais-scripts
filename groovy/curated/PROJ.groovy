// we will need to format the date, here's a function to do it
def dateFormat = new SimpleDateFormat("dd.MM.yyyy")
def formatDate = { Long millis ->
    return millis != null ? dateFormat.format(new Date(millis)) : null
}

// define the table headers along with their datatype (STRING, NUMBER, BOOLEAN, DATE, ...)
def headers = [
  new Header("Názov projektu rozvoja IT",         Header.Type.STRING),
  new Header("Riešiteľ",                          Header.Type.STRING),
  new Header("Typ investície",                    Header.Type.STRING),
  //new Header("Obsiahnutý v programe",             Header.Type.STRING), // this one is never used for some odd reason
  new Header("Financovaný programom",             Header.Type.STRING),
  new Header("Stav evidencie",                    Header.Type.STRING),
  new Header("Dátum zmeny stavu projektu",        Header.Type.DATE),
  new Header("Kód MetaIS",                        Header.Type.STRING),
  new Header("Plánovaný rozpočet projektu v EUR", Header.Type.STRING)
]

// query identifiers for nodes
def qi_proj = qi("proj") // pointer for project
def qi_po = qi("po") // pointer for PO
// there are two types of predicates for project and program - is financed by and is contained within.
// thus, to differentiate between where such PO came from, we need two different pointers for PO
def qi_prog_fin = qi("prog_fin")
def qi_prog_obs = qi("prog_obs")

// query identifiers for relations
def qi_rel_riesitel = qi("rel_riesitel")
def qi_rel_prog_fin = qi("rel_prog_fin")
def qi_rel_prog_obs = qi("rel_prog_obs")

// main nodes for project, PO and program
def type_Projekt = type("Projekt")
def type_PO = type("PO")
def type_Program = type("Program")

// predicates types
def type_PO_je_riesitel_Projektu =   type("PO_asociuje_Projekt")
def type_Program_financuje_Projekt = type("Program_financuje_Projekt")
def type_Program_obsahuje_Projekt =  type("Program_obsahuje_Projekt")

// these enums translate machine name (c_stav_projektu_agile.5) to something human-readable ("Vrátený na dopracovanie")
// enum for prop "EA_Profil_Projekt_status"
def enumProjState =  EnumsRepo.enumTypeMap("STAV_PROJEKTU")
// enum for "EA_Profil_Projekt_typ_investicie" - translates stuff like "c_zmenova_poziadavka" to "Zmenová požiadavka"
def enumInvestType = EnumsRepo.enumTypeMap("TYP_INVESTICIE")

// here we tie our pointer (query identifier) qi_proj to the actual node type_Projekt
// invalidated nodes are removed
def baseMatch = match(path().node(qi_proj, type_Projekt)).where(not(qi_proj.filter(state(StateEnum.INVALIDATED))))
// here we create a relation "asociuje" between Projekt and PO
// that way, whenever we access qi_po's props, it pulls out the correct PO who is associated with the given Project
// we remove invalidated relations
def withRiesitel = baseMatch.match(
  path()
    .node(qi_proj)
    .rel(qi_rel_riesitel, RelationshipDirection.IN, type_PO_je_riesitel_Projektu)
    .node(qi_po, type_PO)
).optional()
 .where(and(
   not(qi_rel_riesitel.filter(state(StateEnum.INVALIDATED))),
   not(qi_po.filter(state(StateEnum.INVALIDATED)))
))
// we can further chain the relations and tack the Programmes onto the previous matches
def withPrograms = withRiesitel.match(
path()
  .node(qi_proj)
  .rel(qi_rel_prog_fin, RelationshipDirection.IN, type_Program_financuje_Projekt)
  .node(qi_prog_fin, type_Program)
).optional()
.where(and(
  not(qi_rel_prog_fin.filter(state(StateEnum.INVALIDATED))),
  not(qi_prog_fin.filter(state(StateEnum.INVALIDATED)))
)).match(
path()
  .node(qi_proj)
  .rel(qi_rel_prog_obs, RelationshipDirection.IN, type_Program_obsahuje_Projekt)
  .node(qi_prog_obs, type_Program)
).optional()
.where(and(
  not(qi_rel_prog_obs.filter(state(StateEnum.INVALIDATED))),
  not(qi_prog_obs.filter(state(StateEnum.INVALIDATED)))
))

// pagination. Undefined (null) page and perPage is sorted out below
def page    = report.page
def perPage = report.perPage

// json metadata
def resCount = Neo4j.execute(
  withRiesitel.returns(count("totalCount", qi_proj))
)

// main data harvest - take all the attributes that you need to construct the table
// see PROJ_raw, PO_raw and PROG_raw for all attributes. Gen_Profil_nazov is always the same and points to the name of the object
def query = withPrograms.returns(
  prop("name",            qi_proj.prop("Gen_Profil_nazov")),
  prop("riesitel_po",     qi_po.prop("Gen_Profil_nazov")),
  prop("program_obs",     qi_prog_obs.prop("Gen_Profil_nazov")),
  prop("program_fin",     qi_prog_fin.prop("Gen_Profil_nazov")),
  prop("invest_type",     qi_proj.prop("EA_Profil_Projekt_typ_investicie")),
  prop("state_code",      qi_proj.prop("EA_Profil_Projekt_status")),
  prop("state_changed",   qi_proj.prop("EA_Profil_Projekt_zmena_stavu")),
  prop("metais_code",     qi_proj.prop("Gen_Profil_kod_metais")),
  prop("budget_planned",  qi_proj.prop("Financny_Profil_Projekt_suma_vydavkov"))
).orderBy(qi_proj.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

// pagination - only if page and perPage are not null these are applied
if (perPage && perPage > 0) query = query.limit(perPage)
if (page && page > 0)       query = query.offset((page - 1) * perPage)

// now we pull the data defined in query
def res = Neo4j.execute(query)

// to manipulate the individual entries, we create a Report of the shape of the headers defined in the beginning
def table = new Report(headers)

// traverse the data and create the table row-by-row
for (row in res.data) {
    // clean up and apply enums
    def riesitel = (row.riesitel_po != null) ? row.riesitel_po : ""
    def invest_type = enumInvestType[row.invest_type]
    def program_obs = (row.program_obs != null) ? row.program_obs : ""
    def program_fin = (row.program_fin != null) ? row.program_fin : ""
    def stateLabel = (row.state_code != null) ? enumProjState[row.state_code] : ""
    def dateLabel  = (row.state_changed != null) ? formatDate(row.state_changed as Long) : ""
    def budgetStr = (row.budget_planned != null) ? String.format(java.util.Locale.US, "%.2f", row.budget_planned as BigDecimal) : ""

    // fill the table. Use cleaned bits where necessary
    table.add([
        row.name, row.riesitel_po, invest_type,
        row.program_fin, stateLabel, dateLabel,
        row.metais_code, budgetStr
    ])
}

// json meta
def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
// main payload
return result