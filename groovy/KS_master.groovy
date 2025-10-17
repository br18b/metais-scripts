import groovy.json.JsonSlurper

def headers = [

  new Header("Názov koncovej služby",                  Header.Type.STRING),
  new Header("Popis koncovej služby",                  Header.Type.STRING),
  new Header("Vstup koncovej služby",                  Header.Type.STRING),
  new Header("Výstup koncovej služby",                 Header.Type.STRING),
  new Header("Zoznam referencií",                      Header.Type.STRING),
  new Header("Kód MetaIS",                             Header.Type.STRING),
  new Header("Referenčný identifikátor",               Header.Type.STRING),
  new Header("Zdroj údajov",                           Header.Type.STRING),
  new Header("Verzia služby",                          Header.Type.STRING),

  new Header("URL na ÚPVS",                            Header.Type.STRING),
  new Header("URL informácií na ÚPVS",                 Header.Type.STRING),
  new Header("URL služby (KS)",                        Header.Type.STRING),
  new Header("URL informácií služby (KS)",             Header.Type.STRING),
  new Header("Prístupové miesto",                      Header.Type.STRING),
  new Header("Cieľový používateľ",                     Header.Type.STRING),
  new Header("Spôsob autentifikácie",                  Header.Type.STRING),
  new Header("Typ notifikácie",                        Header.Type.STRING),
  new Header("Využíva cloud",                          Header.Type.STRING),

  new Header("Typ služby",                             Header.Type.STRING),
  new Header("Mimo výzvy",                             Header.Type.STRING),
  new Header("Úroveň elektronizácie služby",           Header.Type.STRING),
  new Header("Mód zobrazenia na ÚPVS",                 Header.Type.STRING),
  new Header("Je generická (KS)",                      Header.Type.STRING),
  new Header("Je generická (ÚPVS)",                    Header.Type.STRING),
  new Header("Je informatívna",                        Header.Type.STRING),

  new Header("Spôsob platby",                          Header.Type.STRING),
  new Header("Typ spoplatnenia (KS)",                  Header.Type.STRING),
  new Header("Typ spoplatnenia (profil)",              Header.Type.STRING),

  new Header("Platná od (ÚPVS)",                       Header.Type.DATE),
  new Header("Platná od (KS)",                         Header.Type.DATE),
  new Header("Platná do (KS)",                         Header.Type.DATE),

  new Header("Gestor služby",                          Header.Type.STRING),
  new Header("IČO gestora služby",                     Header.Type.STRING)
]

def _cleanScalar = { v ->
    if (v == null) return ""
    def s = v.toString()
    s = s.replaceAll("(?i)<br\\s*/?>", "\n")
         .replaceAll("\\t", " ")
         .replaceAll("\\s{2,}", " ")
         .trim()
    return s
}

def _asList = { val ->
    if (val == null) return []
    if (val instanceof Collection) return val as List
    if (val.getClass().isArray())  return val as List
    if (val instanceof CharSequence) {
        def t = val.toString().trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            try { return (new groovy.json.JsonSlurper().parseText(t)) as List } catch (ignored) {}
        }
        if (t.contains("\n")) {
            return t.split(/\r?\n/).collect { it.trim() }.findAll { it }
        }
    }
    return [val]
}

def joinAny = { val, sep = "; " ->
    def items = _asList(val).collect { _cleanScalar(it) }.findAll { it }.unique()
    return items.join(sep)
}

def mapThenJoin = { val, enumMaps, sep = "; " ->
    def items = _asList(val).collect { code ->
        def label = null
        for (m in enumMaps) { label = m[code]; if (label) break }
        _cleanScalar(label ?: code)
    }.findAll { it }.unique()
    return items.join(sep)
}

def dateFormat = new SimpleDateFormat("dd.MM.yyyy")
def formatDate = { Long millis ->
    return millis != null ? dateFormat.format(new Date(millis)) : ""
}

def extractICO = { val ->
    if (!val) return ""
    def s = val.toString().trim()
    def matcher = s =~ /(\d{8})$/
    return matcher ? matcher[0][1] : ""
}

def normalizeRefList = { val ->
    if (!val) return ""
    def vals = (val instanceof Collection || val.getClass().isArray()) ? val : [val]
    def flat = vals.collect { v ->
        if (v instanceof Map && v.containsKey("left")) {
            return v.left
        } else {
            return v.toString()
        }
    }.findAll { it }.unique()
    return flat.join("; ")
}

def enumSource =      EnumsRepo.enumTypeMap("ZDROJ")
def enumFundCall =    EnumsRepo.enumTypeMap("SLUZBA_MIMO_VYZVY")
def enumUser =        EnumsRepo.enumTypeMap("POUZIVATEL_KS")
def enumAuth =        EnumsRepo.enumTypeMap("AUTENTIFIKACIA")
def enumPaymentType = EnumsRepo.enumTypeMap("LV_PAYMENT_TYPE")
def enumKSType =      EnumsRepo.enumTypeMap("TYP_KS")
def enumLevel =       EnumsRepo.enumTypeMap("SOFISTIKOVANOST")
def enumDisplay =     EnumsRepo.enumTypeMap("ZOBRAZENIE_UPVS")
def enumNotif =       EnumsRepo.enumTypeMap("NOTIFIKACIA")
def enumAccess =      EnumsRepo.enumTypeMap("PRISTUPOVE_MIESTO")
def enumPayment =     EnumsRepo.enumTypeMap("PLATBA")
def enumPubStat =     EnumsRepo.enumTypeMap("STAV_PUBLIKOVANIA_SLUZBY")

def qi_ks =         qi("ks")
def qi_rel_gestor = qi("gestor")
def qi_po =         qi("po")

def type_PO_je_gestor_KS = type("PO_je_gestor_KS")
def type_PO =              type("PO")
def type_KS =              type("KS")

def baseMatch = match(path().node(qi_ks, type_KS)).where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))

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

def page =    report.page
def perPage = report.perPage

def resCount = Neo4j.execute(
    withGestor.returns(count("totalCount", qi_ks))
)

def query = withGestor.returns(

    prop("service_name",          qi_ks.prop("Gen_Profil_nazov")),
    prop("service_desc_sk",       qi_ks.prop("Gen_Profil_popis")),
    prop("metais_code",           qi_ks.prop("Gen_Profil_kod_metais")),
    prop("service_refid",         qi_ks.prop("Gen_Profil_ref_id")),
    prop("service_source",        qi_ks.prop("Gen_Profil_zdroj")),
    prop("service_version",       qi_ks.prop("EA_Profil_KS_verzia")),

    prop("service_input",         qi_ks.prop("EA_Profil_KS_vstup")),
    prop("service_output",        qi_ks.prop("EA_Profil_KS_vystup")),
    prop("service_reflist",       qi_ks.prop("Gen_Profil_EA_zoznam_referencii")),

    prop("upvs_url",              qi_ks.prop("Profil_UPVS_url")),
    prop("upvs_url_info",         qi_ks.prop("Profil_UPVS_url_info")),
    prop("service_url",           qi_ks.prop("KS_Profil_UPVS_url")),
    prop("service_url_info",      qi_ks.prop("KS_Profil_UPVS_url_info")),
    prop("access_point",          qi_ks.prop("EA_Profil_KS_pristupove_miesto")),
    prop("target_user",           qi_ks.prop("EA_Profil_KS_pouzivatel_ks")),
    prop("auth_method",           qi_ks.prop("EA_Profil_KS_autentifikacia")),
    prop("notification_type",     qi_ks.prop("EA_Profil_KS_notifikacia")),
    prop("is_cloud",              qi_ks.prop("EA_Profil_KS_cloud")),

    prop("service_type",          qi_ks.prop("EA_Profil_KS_typ_ks")),
    prop("is_outside_call",       qi_ks.prop("EA_Profil_KS_sluzba_mimo_vyzvy")),
    prop("e_level",               qi_ks.prop("EA_Profil_KS_sofistikovanost")),
    prop("display_mode",          qi_ks.prop("Profil_UPVS_mod_zobrazenia_upvs")),
    prop("is_generic_upvs",       qi_ks.prop("KS_Profil_UPVS_je_genericka")),
    prop("is_generic",            qi_ks.prop("Profil_UPVS_je_genericka")),
    prop("is_informative",        qi_ks.prop("Profil_UPVS_je_informativna")),

    prop("payment_method",        qi_ks.prop("EA_Profil_KS_platba")),
    prop("payment_type_upvs",     qi_ks.prop("KS_Profil_UPVS_typ_spoplatnenia")),
    prop("payment_type_profile",  qi_ks.prop("Profil_UPVS_typ_spoplatnenia")),

    prop("valid_from_upvs",       qi_ks.prop("Profil_UPVS_od")),
    prop("valid_from_service",    qi_ks.prop("KS_Profil_UPVS_od")),
    prop("valid_to_service",      qi_ks.prop("KS_Profil_UPVS_do")),

    prop("gestor_name",           qi_po.prop("Gen_Profil_nazov")),
    prop("gestor_ico",            qi_po.prop("Gen_Profil_ref_id"))
).orderBy(qi_ks.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

if (perPage && perPage > 0) {
  query = query.limit(perPage)
}
if (page && page > 0) {
  query = query.offset((page - 1) * perPage)
}

def res = Neo4j.execute(query)

def table = new Report(headers)

for (row in res.data) {
    // formatting
    def name_clean                 = joinAny(row.service_name, "; ")
    def desc_clean                 = joinAny(row.service_desc_sk, "; ")
    def input_clean                = joinAny(row.service_input, "; ")
    def output_clean               = joinAny(row.service_output, "; ")
    def url_upvs                   = joinAny(row.upvs_url, "; ")
    def url_upvs_info              = joinAny(row.upvs_url_info, "; ")
    def url_service                = joinAny(row.service_url, "; ")
    def url_service_info           = joinAny(row.service_url_info, "; ")
    def version_clean              = joinAny(row.service_version, "; ")
    def source_human               = mapThenJoin(row.service_source, [enumSource])
    def refid_clean                = joinAny(row.service_refid, "; ")
    def gestor_clean               = joinAny(row.gestor_name, "; ")
    def gestor_ico_clean           = extractICO(row.gestor_ico)
    def valid_from_upvs            = formatDate(row.valid_from_upvs as Long)
    def valid_from_service         = formatDate(row.valid_from_service as Long)
    def valid_to_service           = formatDate(row.valid_to_service as Long)
    def is_cloud_clean             = joinAny(row.is_cloud, "; ")
    def is_generic_clean           = joinAny(row.is_generic, "; ")
    def is_generic_upvs_clean      = joinAny(row.is_generic_upvs, "; ")
    def is_informative_clean       = joinAny(row.is_informative, "; ")
    def payment_type_profile_clean = joinAny(row.payment_type_profile, "; ")

    def access_human       = mapThenJoin(row.access_point, [enumAccess])
    def user_human         = mapThenJoin(row.target_user, [enumUser])
    def auth_human         = mapThenJoin(row.auth_method, [enumAuth])
    def notif_human        = mapThenJoin(row.notification_type, [enumNotif])
    def ks_type_human      = mapThenJoin(row.service_type, [enumKSType])
    def fundcall_human     = mapThenJoin(row.is_outside_call, [enumFundCall])
    def level_human        = mapThenJoin(row.e_level, [enumLevel])
    def display_human      = mapThenJoin(row.display_mode, [enumDisplay])
    def payment_human      = mapThenJoin(row.payment_method, [enumPayment])
    def payment_type_human = mapThenJoin(row.payment_type_upvs, [enumPaymentType])
    def reflist_clean      = normalizeRefList(row.service_reflist)

    table.add([
        name_clean, desc_clean, input_clean, output_clean, reflist_clean,
        row.metais_code, refid_clean, source_human, version_clean,
        url_upvs, url_upvs_info, url_service, url_service_info,
        access_human, user_human, auth_human, notif_human, is_cloud_clean,
        ks_type_human, fundcall_human, level_human, display_human,
        is_generic_upvs_clean, is_generic_clean, is_informative_clean,
        payment_human, payment_type_human, payment_type_profile_clean,
        valid_from_upvs, valid_from_service, valid_to_service, gestor_clean, gestor_ico_clean
    ])
}

def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage
return result