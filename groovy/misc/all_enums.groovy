def names = []
def p = report?.parameters ?: [:]
if (p.names instanceof java.util.Collection) {
  p.names.each { if (it != null) names.add(it.toString()) }
} else if (p.names != null) {
  names.add(p.names.toString())
}

// Build result
def result = [:]  // ENUM_NAME -> { code: label }

for (name in names) {
  try {
    def m = EnumsRepo.enumTypeMap(name)
    if (m != null && !m.isEmpty()) {
      def mm = [:]
      m.each { k, v -> mm[k.toString()] = (v?.toString()) }
      result[name] = mm
    } else {
      result[name] = {}   // exists but empty
    }
  } catch (Throwable e) {
    result[name] = null
  }
}

return result