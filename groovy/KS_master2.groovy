def dateFormat = new SimpleDateFormat("dd.MM.yyyy")
def formatDate = { Long millis ->
    return millis != null ? dateFormat.format(new Date(millis)) : null
}

def _asList = { val ->
    if (val == null) return []
    if (val instanceof Collection) return val as List
    if (val.getClass().isArray())  return val as List
    if (val instanceof CharSequence) {
        def t = val.toString().trim()
        // case: string looks like "[a,b,c]"
        if (t.startsWith("[") && t.endsWith("]")) {
            try { 
                return (new groovy.json.JsonSlurper().parseText(t)) as List 
            } catch (ignored) {}
        }
        // case: string has multiple lines
        if (t.contains("\n")) {
            return t.split(/\r?\n/).collect { it.trim() }.findAll { it }
        }
    }
    return [val]
}

def mapEnumList = { val, enumMaps ->
    if (val == null) return []
    def items = (val instanceof Collection ? val : [val]).collect { code ->
        def s = code?.toString()
        def label = null
        for (m in enumMaps) { label = m[s]; if (label) break }
        label ?: s
    }.findAll { it }.unique()
    return items
}

def qi_ks         = qi("ks")
def qi_rel_gestor = qi("gestor")
def qi_po         = qi("po")

def type_PO_je_gestor_KS = type("PO_je_gestor_KS")
def type_PO =              type("PO")
def type_KS =              type("KS")

def enumSource      = EnumsRepo.enumTypeMap("ZDROJ")
def enumFundCall    = EnumsRepo.enumTypeMap("SLUZBA_MIMO_VYZVY")
def enumUser        = EnumsRepo.enumTypeMap("POUZIVATEL_KS")
def enumAuth        = EnumsRepo.enumTypeMap("AUTENTIFIKACIA")
def enumPaymentType = EnumsRepo.enumTypeMap("LV_PAYMENT_TYPE")
def enumKSType      = EnumsRepo.enumTypeMap("TYP_KS")
def enumLevel       = EnumsRepo.enumTypeMap("SOFISTIKOVANOST")
def enumDisplay     = EnumsRepo.enumTypeMap("ZOBRAZENIE_UPVS")
def enumNotif       = EnumsRepo.enumTypeMap("NOTIFIKACIA")
def enumAccess      = EnumsRepo.enumTypeMap("PRISTUPOVE_MIESTO")
def enumPayment     = EnumsRepo.enumTypeMap("PLATBA")
def enumPubStat     = EnumsRepo.enumTypeMap("STAV_PUBLIKOVANIA_SLUZBY")

def baseMatch = match(path().node(qi_ks, type_KS))
  .where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))

def withGestor = baseMatch.match(
  path()
    .node(qi_ks)
    .rel(qi_rel_gestor, RelationshipDirection.IN, type_PO_je_gestor_KS)
    .node(qi_po, type_PO)
).optional()
 .where(and(
   not(qi_rel_gestor.filter(state(StateEnum.INVALIDATED))),
   not(qi_po.filter(state(StateEnum.INVALIDATED)))
 ))

 def COLUMNS = [
  [key:"service_name",          human:"Názov koncovej služby",                  type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_nazov"],
  [key:"service_desc_sk",       human:"Popis koncovej služby",                  type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_popis"],
  [key:"service_input",         human:"Vstup koncovej služby",                  type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_vstup"],
  [key:"service_output",        human:"Výstup koncovej služby",                 type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_vystup"],
  [key:"service_reflist",       human:"Zoznam referencií",                      type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_EA_zoznam_referencii", fmt:"reflist"],
  [key:"metais_code",           human:"Kód MetaIS",                             type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_kod_metais"],
  [key:"service_refid",         human:"Referenčný identifikátor",               type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_ref_id"],
  [key:"service_source",        human:"Zdroj údajov",                           type:Header.Type.STRING, from:"ks", prop:"Gen_Profil_zdroj"],
  [key:"service_version",       human:"Verzia služby",                          type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_verzia"],

  [key:"upvs_url",              human:"URL na ÚPVS",                            type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_url"],
  [key:"upvs_url_info",         human:"URL informácií na ÚPVS",                 type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_url_info"],
  [key:"service_url",           human:"URL služby (KS)",                        type:Header.Type.STRING, from:"ks", prop:"KS_Profil_UPVS_url"],
  [key:"service_url_info",      human:"URL informácií služby (KS)",             type:Header.Type.STRING, from:"ks", prop:"KS_Profil_UPVS_url_info"],
  [key:"access_point",          human:"Prístupové miesto",                      type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_pristupove_miesto"],
  [key:"target_user",           human:"Cieľový používateľ",                     type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_pouzivatel_ks"],
  [key:"auth_method",           human:"Spôsob autentifikácie",                  type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_autentifikacia"],
  [key:"notification_type",     human:"Typ notifikácie",                        type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_notifikacia"],
  [key:"is_cloud",              human:"Využíva cloud",                          type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_cloud"],

  [key:"service_type",          human:"Typ služby",                             type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_typ_ks"],
  [key:"is_outside_call",       human:"Mimo výzvy",                             type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_sluzba_mimo_vyzvy"],
  [key:"e_level",               human:"Úroveň elektronizácie služby",           type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_sofistikovanost"],
  [key:"display_mode",          human:"Mód zobrazenia na ÚPVS",                 type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_mod_zobrazenia_upvs"],
  [key:"is_generic_upvs",       human:"Je generická (KS)",                      type:Header.Type.STRING, from:"ks", prop:"KS_Profil_UPVS_je_genericka"],
  [key:"is_generic",            human:"Je generická (ÚPVS)",                    type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_je_genericka"],
  [key:"is_informative",        human:"Je informatívna",                        type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_je_informativna"],

  [key:"payment_method",        human:"Spôsob platby",                          type:Header.Type.STRING, from:"ks", prop:"EA_Profil_KS_platba"],
  [key:"payment_type_upvs",     human:"Typ spoplatnenia (KS)",                  type:Header.Type.STRING, from:"ks", prop:"KS_Profil_UPVS_typ_spoplatnenia"],
  [key:"payment_type_profile",  human:"Typ spoplatnenia (profil)",              type:Header.Type.STRING, from:"ks", prop:"Profil_UPVS_typ_spoplatnenia"],

  [key:"valid_from_upvs",       human:"Platná od (ÚPVS)",                       type:Header.Type.DATE,   from:"ks", prop:"Profil_UPVS_od"],
  [key:"valid_from_service",    human:"Platná od (KS)",                         type:Header.Type.DATE,   from:"ks", prop:"KS_Profil_UPVS_od"],
  [key:"valid_to_service",      human:"Platná do (KS)",                         type:Header.Type.DATE,   from:"ks", prop:"KS_Profil_UPVS_do"],

  [key:"gestor_name",           human:"Gestor služby",                          type:Header.Type.STRING, from:"po", prop:"Gen_Profil_nazov"],
  [key:"gestor_ico",            human:"IČO gestora služby",                     type:Header.Type.STRING, from:"po", prop:"Gen_Profil_ref_id", fmt:"ico"]
]

def ENUM_BY_PROP = [
  "Gen_Profil_zdroj"                  : enumSource,
  "EA_Profil_KS_pristupove_miesto"    : enumAccess,
  "EA_Profil_KS_pouzivatel_ks"        : enumUser,
  "EA_Profil_KS_autentifikacia"       : enumAuth,
  "EA_Profil_KS_notifikacia"          : enumNotif,
  "EA_Profil_KS_typ_ks"               : enumKSType,
  "EA_Profil_KS_sluzba_mimo_vyzvy"    : enumFundCall,
  "EA_Profil_KS_sofistikovanost"      : enumLevel,
  "Profil_UPVS_mod_zobrazenia_upvs"   : enumDisplay,
  "EA_Profil_KS_platba"               : enumPayment,
  "KS_Profil_UPVS_typ_spoplatnenia"   : enumPaymentType,
  "Profil_UPVS_typ_spoplatnenia"      : enumPaymentType,
]

def ENUM_BY_C_TOKEN = [
  "pristup"             : enumAccess,
  "pristupove_miesto"   : enumAccess,
  "pouzivatel_ks"       : enumUser,
  "pouzivatel"          : enumUser,
  "autentifikacia"      : enumAuth,
  "notifikacia"         : enumNotif,
  "typ_ks"              : enumKSType,
  "sluzba_mimo_vyzvy"   : enumFundCall,
  "sofistikovanost"     : enumLevel,
  "zobrazenie_upvs"     : enumDisplay,
  "mod_zobrazenia_upvs" : enumDisplay,
  "platba"              : enumPayment,
  "typ_spoplatnenia"    : enumPaymentType,
  "zdroj"               : enumSource
]

def ALL_ENUMS = [enumSource, enumFundCall, enumUser, enumAuth, enumPaymentType, enumKSType,
                 enumLevel, enumDisplay, enumNotif, enumAccess, enumPayment, enumPubStat]

def page    = report.page
def perPage = report.perPage

def headers = COLUMNS.collect { new Header(it.human, it.type as Header.Type) }

def projections = COLUMNS.collect { col ->
  def node = (col.from == "po") ? qi_po : qi_ks
  prop(col.key, node.prop(col.prop))
}

def resCount = Neo4j.execute(
  withGestor.returns(count("totalCount", qi_ks))
)

def query = withGestor.returns(*projections)
  .orderBy(qi_ks.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)

if (perPage && perPage > 0) query = query.limit(perPage)
if (page && page > 0)       query = query.offset((page - 1) * perPage)

def res = Neo4j.execute(query)

def unknownMappings = []

Object humanizeValue(Map col, Object raw) {
  // Dates
  if (col.type == Header.Type.DATE) {
    try { return formatDate(raw as Long) } catch (ignored) { return "" }
  }

  if (raw instanceof Collection || raw.getClass().isArray()) {
    def parts = _asList(raw).collect { item -> humanizeValue(col, item) }
    def flat  = parts.collectMany { _asList(it) }
    return flat.unique()
  }

  // If property explicitly mapped to an enum, use it
  def explicitEnum = ENUM_BY_PROP[col.prop]
  if (explicitEnum) return mapEnumList(raw, [explicitEnum])

  // If we see c_xxx.N style, try route by token, else try all enums
  def asStrs = _asList(raw).collect { it?.toString() ?: "" }.findAll { it }
  def hasCodes = asStrs.any { it ==~ /^c_[^.]+\.\d+$/ }

  if (hasCodes) {
    // Try to pick enum by c_<token>.N
    def labels = []
    for (s in asStrs) {
      def m = (s =~ /^c_([^.]+)\.(\d+)$/)
      if (m) {
        def token = m[0][1].toLowerCase()
        def enumMap = ENUM_BY_C_TOKEN[token]
        if (enumMap) {
          labels << (enumMap[s] ?: s)
        } else {
          // Try all as fallback
          def l = null
          for (em in ALL_ENUMS) { l = em[s]; if (l) break }
          labels << (l ?: s)
          if (!l) unknownMappings << [prop: col.prop, value: s, note: "No enum route for c_${token}.N"]
        }
      } else {
        labels << s
      }
    }
    return labels.unique()
  }

  // Plain string/number
  return raw
}

def table = new Report(headers)
for (row in res.data) {
  def rendered = COLUMNS.collect { col -> humanizeValue(col, row[col.key]) }
  table.add(rendered)
}

def total = (resCount.data && resCount.data[0]?.totalCount) ? (resCount.data[0].totalCount as int) : 0
def result = new ReportResult("TABLE", table, total)
result.page = page
result.perPage = perPage

if (!unknownMappings.isEmpty()) {
  def msg = unknownMappings
    .collect { "- prop: ${it.prop} | value: ${it.value} | note: ${it.note}" }
    .unique()
    .join("\n")
  System.err.println("[WARN] Unmapped machine codes detected:\n" + msg + "\n(Add to ENUM_BY_PROP or ENUM_BY_C_TOKEN.)")
}

return result