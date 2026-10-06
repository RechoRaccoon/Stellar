// Stand-ins so the plain-Kotlin half of the iOS VRM code (shared/src/iosMain/
// …/vrm) can be compiled and run on any machine with just kotlinc — the
// real kotlinx.serialization and the app's own Log aren't available outside
// a Gradle build. Only what that code uses is here.
@file:Suppress("unused")

package kotlinx.serialization.json

sealed class JsonElement
object JsonNull : JsonElement()
class JsonPrimitive(val content: String, val isString: Boolean) : JsonElement()
class JsonObject(private val content: Map<String, JsonElement>) : JsonElement(), Map<String, JsonElement> by content
class JsonArray(private val content: List<JsonElement>) : JsonElement(), List<JsonElement> by content

val JsonPrimitive.intOrNull: Int? get() = if (isString) null else content.toIntOrNull()
val JsonPrimitive.floatOrNull: Float? get() = if (isString) null else content.toFloatOrNull()
val JsonPrimitive.booleanOrNull: Boolean? get() = if (isString) null else content.toBooleanStrictOrNull()

object Json {
    fun parseToJsonElement(text: String): JsonElement = Parser(text).run { skip(); value().also { skip() } }

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): JsonElement {
            skip()
            return when (s[i]) {
                '{' -> {
                    i++; val m = LinkedHashMap<String, JsonElement>(); skip()
                    if (s[i] == '}') { i++; return JsonObject(m) }
                    while (true) {
                        skip(); val k = string(); skip(); check(s[i] == ':'); i++
                        m[k] = value(); skip()
                        if (s[i] == ',') { i++; continue }
                        check(s[i] == '}'); i++; return JsonObject(m)
                    }
                    @Suppress("UNREACHABLE_CODE") JsonNull
                }
                '[' -> {
                    i++; val l = ArrayList<JsonElement>(); skip()
                    if (s[i] == ']') { i++; return JsonArray(l) }
                    while (true) {
                        l.add(value()); skip()
                        if (s[i] == ',') { i++; continue }
                        check(s[i] == ']'); i++; return JsonArray(l)
                    }
                    @Suppress("UNREACHABLE_CODE") JsonNull
                }
                '"' -> JsonPrimitive(string(), true)
                else -> {
                    val start = i
                    while (i < s.length && s[i] !in ",}] \n\r\t") i++
                    val word = s.substring(start, i)
                    if (word == "null") JsonNull else JsonPrimitive(word, false)
                }
            }
        }
        fun string(): String {
            check(s[i] == '"'); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}
