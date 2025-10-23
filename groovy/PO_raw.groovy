def qi_po  = qi("po")
def type_PO = type("PO")

def q = match(path().node(qi_po, type_PO))
  .where(not(qi_po.filter(state(StateEnum.INVALIDATED))))

def res = Neo4j.execute(
  q.returns(node(qi_po))
)

return res.data.collect { it.po }