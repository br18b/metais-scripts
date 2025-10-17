def qi_ks         = qi("ks")
def qi_po         = qi("po")
def qi_rel_gestor = qi("gestor")

def type_KS              = type("KS")
def type_PO              = type("PO")
def type_PO_je_gestor_KS = type("PO_je_gestor_KS")

// KS <-[PO_je_gestor_KS]- PO, skip invalidated
def q = match(path().node(qi_ks, type_KS))
  .where(not(qi_ks.filter(state(StateEnum.INVALIDATED))))
  .match(
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

// Return the raw PO node objects
def res = Neo4j.execute(q.returns(qi_po))

// Deduplicate by UUID and drop nulls
def nodes = res.data.collect { it.po }.findAll { it }
def seen = new HashSet()
def uniq = []
nodes.each { n ->
  def id = n.uuid ?: n."\$cmdb_id" ?: n.toString()
  if (!seen.contains(id)) {
    seen.add(id)
    uniq << n
  }
}

// Return as a plain JSON array (the report API will wrap it, but `result` will be this array)
return uniq