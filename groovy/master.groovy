// 18.06.2024 peter.viskup@mirri.gov.sk based on request from NASES Szilva, Benko
def reportHeaders = [
        new Header("MetaIS kód", Header.Type.STRING),
        new Header("Názov služby", Header.Type.STRING),
        new Header("Popis služby", Header.Type.STRING),
        //new Header("Typ služby", Header.Type.STRING),
        new Header("Typ služby UPVS", Header.Type.STRING),
        new Header("URL služby (ak sa jedná o externú službu)", Header.Type.STRING),
        new Header("URL popisu služby", Header.Type.STRING),
        new Header("Platnosť služby od", Header.Type.DATE),
        new Header("Platnosť služby do", Header.Type.DATE),
        new Header("Kategória služby", Header.Type.STRING),
        new Header("Názov dokumentu (formuláru)", Header.Type.STRING),
        new Header("Popis dokumentu (formuláru)", Header.Type.STRING),
        new Header("Identifikátor formuláru", Header.Type.STRING),
        new Header("Typ dokumentu", Header.Type.STRING),
        new Header("Prílohy", Header.Type.BOOLEAN),
        new Header("Vyžadovaný KEP", Header.Type.BOOLEAN),
        new Header("Typ poplatku", Header.Type.STRING),
        new Header("Kód platby (ID služby v IS PEP)", Header.Type.STRING),
        new Header("Životná situácia kategória", Header.Type.STRING),
        new Header("Životná situácia podkategória", Header.Type.STRING),
        new Header("IČO inštitúcie (IČO alebo IČO_suffix)", Header.Type.STRING),
        //new Header("Stav publikovania na UPVS", Header.Type.STRING),
        new Header("Je anonymná", Header.Type.BOOLEAN),
        new Header("Požiadavky na prílohy (json)", Header.Type.STRING)
]

def ksTypeEnums = EnumsRepo.enumTypeMap("TYP_KS")
def paymentTypeEnums = EnumsRepo.enumTypeMap("LV_PAYMENT_TYPE")
def publStateEnums = EnumsRepo.enumTypeMap("STAV_PUBLIKOVANIA_SLUZBY")
def edeskMimeTypeEnums = EnumsRepo.enumTypeMap("EDESK_MIME_TYPE")

def qi_ks = qi("ks")
def qi_form = qi("form")
def qi_zs = qi("zs")
def qi_zsgroup = qi("zsgroup")
def qi_po = qi("po")

def qi_rel_riesi = qi("riesi")
def qi_rel_zoskupuje = qi("zoskupuje")
def qi_rel_patri = qi("patri")
def qi_rel_gestor = qi("gestor")

def type_KS = type("KS")
def type_Formular = type("Formular")
def type_ZS = type("ZS")
def type_OkruhZS = type("OkruhZS")
def type_PO = type("PO")

def type_Formular_riesi_KS = type("Formular_riesi_KS")
def type_ZS_zoskupuje_KS = type("ZS_zoskupuje_KS")
def type_ZS_patri_OkruhZS = type("ZS_patri_OkruhZS")
def type_PO_je_gestor_KS = type("PO_je_gestor_KS")

//def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
def dateFormat = new SimpleDateFormat("dd.MM.yyyy")
def formatDate = { Long millis ->
    return millis != null ? dateFormat.format(new Date(millis)) : null
}

def parseDateToMillis = { String date ->
    return dateFormat.parse(date).time
}

// paging
def page = (report.page ?: 1) - 1
def perPage = report.perPage ?: 1000

// params
def paramCode = report.parameters?.code as String
def paramDateFrom = report.parameters?.dateFrom as String
def paramDateTo = report.parameters?.dateTo as String
def paramIco = report.parameters?.ico as String
def paramState = report.parameters?.state as Map

def codeFilter = new EmptyExpression();
if (paramCode && !paramCode.blank) {
    codeFilter = qi_ks.prop("Gen_Profil_kod_metais").filter(unaccentedStringContains(paramCode))
}

def dateFromFilter = new EmptyExpression();
if (paramDateFrom && !paramDateFrom.blank) {
    dateFromFilter = qi_ks.prop("KS_Profil_UPVS_od").filter(geq(parseDateToMillis(paramDateFrom)))
}

def dateToFilter = new EmptyExpression();
if (paramDateTo && !paramDateTo.blank) {
    dateToFilter = qi_ks.prop("KS_Profil_UPVS_do").filter(leq(parseDateToMillis(paramDateTo)))
}

def icoFilter = new EmptyExpression();
if (paramIco && !paramIco.blank) {
    icoFilter = qi_po.prop("EA_Profil_PO_ico").filter(eq(paramIco))
}

def stateFilter = new EmptyExpression();
if (paramState && paramState.code) {
    stateFilter = qi_ks.prop("KS_Profil_UPVS_stav_publikovania").filter(eq(paramState.code))
}

def query = match(path().node(qi_ks, type_KS))
        .where(and(
                not(qi_ks.filter(state(StateEnum.INVALIDATED))),
                codeFilter,
                dateFromFilter,
                dateToFilter,
                stateFilter
        ))

        .match(path().node(qi_ks).rel(qi_rel_riesi, RelationshipDirection.IN, type_Formular_riesi_KS).node(qi_form, type_Formular))
        .optional()
        .where(and(not(qi_rel_riesi.filter(state(StateEnum.INVALIDATED))), not(qi_form.filter(state(StateEnum.INVALIDATED)))))

        .match(path().node(qi_zs, type_ZS).rel(qi_rel_zoskupuje, RelationshipDirection.OUT, type_ZS_zoskupuje_KS).node(qi_ks))
        .where(and(not(qi_rel_zoskupuje.filter(state(StateEnum.INVALIDATED))), not(qi_zs.filter(state(StateEnum.INVALIDATED)))))

        .match(path().node(qi_zs).rel(qi_rel_patri, RelationshipDirection.OUT, type_ZS_patri_OkruhZS).node(qi_zsgroup, type_OkruhZS))
        .where(and(not(qi_rel_patri.filter(state(StateEnum.INVALIDATED))), not(qi_zsgroup.filter(state(StateEnum.INVALIDATED)))))

        .match(path().node(qi_ks).rel(qi_rel_gestor, RelationshipDirection.IN, type_PO_je_gestor_KS).node(qi_po, type_PO))
        .where(and(
                not(qi_rel_gestor.filter(state(StateEnum.INVALIDATED))),
                not(qi_po.filter(state(StateEnum.INVALIDATED))),
                icoFilter
        ))

def returnDef = query.returns(
        prop("code", qi_ks.prop("Gen_Profil_kod_metais")),
        prop("name", qi_ks.prop("Gen_Profil_nazov")),
        prop("description", qi_ks.prop("Gen_Profil_popis")),
        prop("ksType", qi_ks.prop("EA_Profil_KS_typ_ks")),
        prop("url", qi_ks.prop("KS_Profil_UPVS_url")),
        prop("urlInfo", qi_ks.prop("KS_Profil_UPVS_url_info")),
        prop("validFrom", qi_ks.prop("KS_Profil_UPVS_od")),
        prop("validTo", qi_ks.prop("KS_Profil_UPVS_do")),
        prop("requiresZep", qi_rel_riesi.prop("Profil_Rel_FormularKS_vyzaduje_zep")),
        prop("requiresZepFallback", qi_ks.prop("EA_Profil_KS_vyzaduje_zep")),
        prop("paymentType", qi_ks.prop("KS_Profil_UPVS_typ_spoplatnenia")),
        prop("paymentCode", qi_ks.prop("KS_Profil_UPVS_kod_spoplatnenia")),
        prop("publicationState", qi_ks.prop("KS_Profil_UPVS_stav_publikovania")),
        prop("formName", qi_form.prop("Gen_Profil_nazov")),
        prop("formDesc", qi_form.prop("Gen_Profil_popis")),
        prop("formCode", qi_form.prop("EA_Profil_Formular_kod_formulara")),
        prop("formAllowAttachment", qi_form.prop("EA_Profil_Formular_povolit_prilohy")),
        prop("zsName", qi_zs.prop("Gen_Profil_nazov")),
        prop("zsGroupName", qi_zsgroup.prop("Gen_Profil_nazov")),
        prop("poIco", qi_po.prop("EA_Profil_PO_ico")),
        prop("isAnonymous", qi_ks.prop("KS_Profil_UPVS_je_anonymna")),
        prop("attachmentType", qi_rel_riesi.prop("Profil_Rel_FormularKS_typy_priloh")),
        prop("ksTypeUPVS", qi_ks.prop("KS_Profil_UPVS_typy_sluzby_upvs"))
)
        .orderBy(qi_ks.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)
        .orderBy(qi_form.prop("\$cmdb_lastModifiedAt"), OrderDirection.ASC)
        .offset(page * perPage)
        .limit(perPage)

def data = Neo4j.execute(returnDef).data

def jsonSlurper = new JsonSlurper()
def jsonGenerator = new JsonGenerator.Options().excludeNulls().build()
def attachmentTypes = [:] // map of loaded and processed attachment types

// construct json array of attachment types
def getAttachmentTypes = { List<String> uids ->
    if (!uids || uids.empty) {
        return null
    }

    def attachments = []
    for (final def uid in uids) {
        def type = attachmentTypes[uid]
        if (type) {
            attachments.add(type)
        }
    }

    return attachments.isEmpty() ? null : jsonGenerator.toJson(attachments)
}

def attachmentTypeUids = data
        .findAll { it.attachmentType }
        .collect { it.attachmentType as List }
        .flatten()
        .unique()

if (!attachmentTypeUids.isEmpty()) {
    // load and process all attachment types in current dataset at once
    Neo4j.execute(() -> new ParametrizedQuery("match (tp:Priloha_typ) where tp.`\$cmdb_id` in \$uids and tp.`\$cmdb_state` <> \"INVALIDATED\" return tp", [uids: attachmentTypeUids])).data.forEach {
        def type = it.tp as Map
        def name = type.Gen_Profil_nazov as String
        def description = type.Gen_Profil_popis as String
        def allowedMimetypes = type.Profil_typ_prilohy_attachment_format as List
        if (allowedMimetypes) {
            allowedMimetypes = allowedMimetypes.collect { edeskMimeTypeEnums[it] }
        }

        def allowedForm = type.Profil_typ_prilohy_allowedForm as String
        def isRequired = type.Profil_typ_prilohy_required as Boolean
        //def isRequired = type.Profil_typ_prilohy_required as Integer
        def isSignatureRequired = type.Profil_typ_prilohy_signatureRequired as Boolean
        //def isSignatureRequired = type.Profil_typ_prilohy_signatureRequired as Integer
        def isMixedAuthorisationAllowed = type.Profil_typ_prilohy_attachment_sign_mass as Boolean
        //def isMixedAuthorisationAllowed = type.Profil_typ_prilohy_attachment_sign_mass as Integer
        def multiplicity = type.Profil_typ_prilohy_attachment_multiplicity as Number

        // Parse the allowedForm JSON string into an object
        def allowedFormObject
        try {
            allowedFormObject = allowedForm ? jsonSlurper.parseText(allowedForm) : null
        } catch (Exception ignored) {
            allowedFormObject = jsonSlurper.parseText("{\"error\":\"failed to parse allowed form json\"}")
        }

        attachmentTypes.put(type.$cmdb_id, [
                name                       : name,
                description                : description,
                allowedMimetypes           : allowedMimetypes,
                allowedForm                : allowedFormObject,
                isRequired                 : Boolean.TRUE == isRequired,
                isSignatureRequired        : Boolean.TRUE == isSignatureRequired,
                isMixedAuthorisationAllowed: Boolean.TRUE == isMixedAuthorisationAllowed,
                multiplicity               : multiplicity as String
        ])
    }
}

def getreplacedksType = { String ksType ->
    if (!ksType || ksType.empty) {
        return null
    }
    str = ksType.replaceAll('FORM', 'Formulárové služby')
    str = str.replaceAll('EXTERNAL', 'Externé služby')

    return str.isEmpty() ? null : str
}

def reportDef = new Report(reportHeaders)
data.collect(reportDef.getRows(), {
    new Report.Row([
            it.code, // KS.Gen_Profil_kod_metais
            it.name, // KS.Gen_Profil_nazov
            it.description, // KS.Gen_Profil_popis
            //ksTypeEnums[it.ksType], // KS.EA_Profil_KS_typ_ks
            //it.ksTypeUPVS, //KS.KS_Profil_UPVS_typy_sluzby_upvs
            //(it.ksTypeUPVS != null) ? (replace( it.ksTypeUPVS , ["FORM": "Formulárové služby", "EXTERNAL": "Externé služby"])) : null, //KS.KS_Profil_UPVS_typy_sluzby_upvs
            getreplacedksType(it.ksTypeUPVS), //KS.KS_Profil_UPVS_typy_sluzby_upvs
            it.url, // KS.KS_Profil_UPVS_url
            it.urlInfo, // KS.KS_Profil_UPVS_url_info
            formatDate(it.validFrom as Long), // KS.KS_Profil_UPVS_od
            formatDate(it.validTo as Long), // KS.KS_Profil_UPVS_do
            "Podanie", // Kategória služby - NEMAME
            it.formName, // Formular.Gen_Profil_nazov
            it.formDesc, // Formular.Gen_Profil_popis
            it.formCode, // Formular.EA_Profil_Formular_kod_formulara
            it.formName != null ? "Hlavný formulár" : null,
            //it.formAllowAttachment as String, // Formular.EA_Profil_Formular_povolit_prilohy
            (it.formAllowAttachment != null) ? (it.formAllowAttachment ? 1 : 0) : null, // Formular.EA_Profil_Formular_povolit_prilohy
            //getAttachmentTypes(it.attachmentType as List),
            //(it.requiresZep ?: it.requiresZepFallback) as String, // KS.EA_Profil_KS_vyzaduje_zep
            ((it.requiresZep ?: it.requiresZepFallback) != null) ? ((it.requiresZep ?: it.requiresZepFallback) ? 1 : 0) : null, // KS.EA_Profil_KS_vyzaduje_zep
            paymentTypeEnums[it.paymentType], // KS.KS_Profil_UPVS_typ_spoplatnenia
            it.paymentCode, // KS.KS_Profil_UPVS_kod_spoplatnenia
            it.zsGroupName, // OkruhZS.Gen_Profil_nazov
            it.zsName, // ZS.Gen_Profil_nazov
            "ico://sk/".concat(it.poIco), // PO.EA_Profil_PO_ico
            //publStateEnums[it.publicationState], // KS.KS_Profil_UPVS_stav_publikovania
            (it.isAnonymous != null) ? (it.isAnonymous ? 1 : 0) : null, //KS.KS_Profil_UPVS_je_anonymna
            getAttachmentTypes(it.attachmentType as List),
    ])
})

def totalCount = Neo4j.execute(query.returns(count("totalCount", qi_ks))).data.first().totalCount as int
def result = new ReportResult("TABLE", reportDef, totalCount)
result.page = page
result.perPage = perPage
return result
