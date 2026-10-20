// Private Sync's three-way merge (run with run.sh).
import com.mediaviewer.util.JsonMerge
import kotlinx.serialization.json.Json

private fun j(s: String) = Json.parseToJsonElement(s)
private fun m(b: String?, l: String?, r: String?, localWins: Boolean = true) =
    JsonMerge.merge3(b?.let { j(it) }, l?.let { j(it) }, r?.let { j(it) }, localWins)?.toString() ?: "null"

private fun same(name: String, got: String, want: String) {
    val ok = got == want
    if (!ok) failed++
    println((if (ok) "ok   " else "FAIL ") + name + if (!ok) "\n  got  $got\n  want $want" else "")
}

fun mergeTests() {
    // Only one side changed: that side.
    same("one side", m("""{"a":1}""", """{"a":1}""", """{"a":2}"""), """{"a":2}""")
    same("other side", m("""{"a":1}""", """{"a":3}""", """{"a":1}"""), """{"a":3}""")
    // Different keys changed on each side: both.
    same("both keys", m("""{"a":1,"b":1}""", """{"a":2,"b":1}""", """{"a":1,"b":2}"""), """{"a":2,"b":2}""")
    // Same key, both: localWins decides.
    same("conflict local", m("""{"a":1}""", """{"a":2}""", """{"a":3}"""), """{"a":2}""")
    same("conflict remote", m("""{"a":1}""", """{"a":2}""", """{"a":3}""", localWins = false), """{"a":3}""")
    // Entries by id: added on each side, edited on one, deleted on the other.
    val base = """[{"id":"1","t":"a"},{"id":"2","t":"b"},{"id":"3","t":"c"}]"""
    val local = """[{"id":"0","t":"new here"},{"id":"1","t":"a edited"},{"id":"2","t":"b"}]""" // removed 3
    val remote = """[{"id":"1","t":"a"},{"id":"3","t":"c"},{"id":"4","t":"new there"}]"""   // removed 2
    same("entries", m(base, local, remote), """[{"id":"0","t":"new here"},{"id":"1","t":"a edited"},{"id":"4","t":"new there"}]""")
    // Edit outlives delete.
    same("edit vs delete", m("""[{"id":"1","t":"a"}]""", """[{"id":"1","t":"a2"}]""", "[]"), """[{"id":"1","t":"a2"}]""")
    // Nothing synced before (a device joining): everything from both sides kept.
    same("first join", m(null, """[{"id":"1"}]""", """[{"id":"2"}]"""), """[{"id":"2"},{"id":"1"}]""") // (the other side's new ones where they stood there)
    // Plain values merge like sets.
    same("set", m("""["a","b"]""", """["a","b","c"]""", """["b","d"]"""), """["b","c","d"]""")
    // A whole section only one side has passes through.
    same("absent", m(null, null, """{"x":1}"""), """{"x":1}""")
    same("deleted untouched", m("""{"x":1}""", null, """{"x":1}"""), "null")
    // Nested: a settings value inside a JSON text value.
    same("nested", m("""{"k":{"j":[{"id":"a","v":1}]}}""", """{"k":{"j":[{"id":"a","v":2}]}}""", """{"k":{"j":[{"id":"a","v":1},{"id":"b","v":1}]}}"""),
        """{"k":{"j":[{"id":"a","v":2},{"id":"b","v":1}]}}""")
}
