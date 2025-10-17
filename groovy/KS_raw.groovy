// Full raw dump of all KS nodes (no headers, no post-processing)
def qi_ks  = qi("ks")
def type_KS = type("KS")

def q = match(path().node(qi_ks, type_KS))
  .where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))

// directly return the node map
def res = Neo4j.execute(
  q.returns(node(qi_ks))
)

// return plain JSON array of node data
return res.data.collect { it.ks }