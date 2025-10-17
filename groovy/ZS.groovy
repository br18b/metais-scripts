def headers = [ new Header("Životná situácia názov", Header.Type.STRING), new Header("Životná situácia kód", Header.Type.STRING), new Header("Názov koncovej služby", Header.Type.STRING), new Header("Kód koncovej služby", Header.Type.STRING)]
def response = new Report(headers);
def perPage = report.perPage == null ? 10 : report.perPage;
def page = report.page == null ? 0 : report.page-1;

def match = match(
			 path().node(qi("egov"), type("KS")).rel(RelationshipDirection.IN, type("ZS_zoskupuje_KS")).node(qi("zivsit"), type("ZS"))
            ).where(
			and(
				not(qi("zivsit").filter(state(StateEnum.INVALIDATED))),
				parameters.kod == null || parameters.kod==""  ? new EmptyExpression() : qi("zivsit").filter(id(parameters.kod))
			)

            );

def resCount = Neo4j.execute(match.returns(count("totalCount", qi("*"))));

def res = Neo4j.execute(match.returns(
                prop("kod", qi("zivsit").prop("Gen_Profil_kod_metais")),
                prop("nazov", qi("zivsit").prop("Gen_Profil_nazov")),
				prop("egovkod", qi("egov").prop("Gen_Profil_kod_metais")),
                prop("egovnazov", qi("egov").prop("Gen_Profil_nazov"))
            ).limit(perPage).offset(page * perPage));

for ( ci in res.data ) {
  response.add([
        ci.nazov,
        ci.kod,
		ci.egovnazov,
        ci.egovkod]);
}

reportResult = new ReportResult("TABLE", response);
reportResult.totalCount = resCount.data[0].totalCount;
return reportResult;