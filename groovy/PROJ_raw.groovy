def qi_proj  = qi("proj")
def type_PROJ = type("Projekt") // tried: projekt, pr, proj, PROJEKT, PR, PROJ

def q = match(path().node(qi_proj, type_PROJ))
  .where(not(qi_proj.filter(state(StateEnum.INVALIDATED))))

def res = Neo4j.execute(
  q.returns(node(qi_proj))
)

return res.data.collect { it.proj }