def ksTypeEnums = EnumsRepo.enumTypeMap("TYP_KS")

def headers = [
  new Header("key",  Header.Type.STRING),
  new Header("value", Header.Type.STRING)
]
def report = new Report(headers)

// Either way works:
ksTypeEnums.each { k, v ->
  report.add(new Report.Row([k, v]))                 // ✅ method exists
  // or: report.getRows().add(new Report.Row([k, v]))
}

def out = new ReportResult("TABLE", report, ksTypeEnums.size())
out.page = 0
out.perPage = ksTypeEnums.size()
return out