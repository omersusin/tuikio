package dev.tuikio.json

/**
 * A small strict JSON reader/writer. Catalogs, manifests and control profiles come from
 * the network and from users, so parsing is bounded (input size, nesting depth, string
 * length) and never evaluates anything. No reflection, so nothing for R8/ProGuard to keep.
 */
sealed class Json {
    data class Obj(val fields: Map<String, Json>) : Json() {
        operator fun get(key: String): Json? = fields[key]
    }

    data class Arr(val items: List<Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    object Null : Json() {
        override fun toString() = "Null"
    }

    class ParseException(message: String, val offset: Int) : Exception("$message at offset $offset")

    class Limits(
        val maxBytes: Int = 4 * 1024 * 1024,
        val maxDepth: Int = 32,
        val maxStringLength: Int = 64 * 1024,
        val maxItems: Int = 100_000,
    )

    companion object {
        fun parse(text: String, limits: Limits = Limits()): Json {
            if (text.length > limits.maxBytes) throw ParseException("document too large", 0)
            val p = Parser(text, limits)
            val v = p.value(0)
            p.skipWs()
            if (p.pos != text.length) throw ParseException("trailing characters", p.pos)
            return v
        }

        fun obj(vararg pairs: Pair<String, Any?>): Obj = Obj(linkedMapOf(*pairs.map { it.first to of(it.second) }.toTypedArray()))

        fun of(v: Any?): Json = when (v) {
            null -> Null
            is Json -> v
            is String -> Str(v)
            is Boolean -> Bool(v)
            is Int -> Num(v.toDouble())
            is Long -> Num(v.toDouble())
            is Double -> Num(v)
            is Float -> Num(v.toDouble())
            is Map<*, *> -> Obj(LinkedHashMap<String, Json>().also { m -> v.forEach { (k, x) -> m[k.toString()] = of(x) } })
            is Iterable<*> -> Arr(v.map { of(it) })
            else -> throw IllegalArgumentException("not JSON-serializable: ${v::class.java.name}")
        }
    }

    /** Compact or pretty JSON with keys in insertion order. */
    fun write(pretty: Boolean = false): String = StringBuilder().also { writeTo(it, pretty, 0) }.toString()

    private fun writeTo(sb: StringBuilder, pretty: Boolean, indent: Int) {
        fun nl(level: Int) {
            if (pretty) {
                sb.append('\n')
                repeat(level) { sb.append("  ") }
            }
        }
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(value)
            is Num -> if (value == Math.rint(value) && Math.abs(value) < 1e15) sb.append(value.toLong()) else sb.append(value)
            is Str -> quote(sb, value)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    nl(indent + 1)
                    item.writeTo(sb, pretty, indent + 1)
                }
                if (items.isNotEmpty()) nl(indent)
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    nl(indent + 1)
                    quote(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    v.writeTo(sb, pretty, indent + 1)
                }
                if (fields.isNotEmpty()) nl(indent)
                sb.append('}')
            }
        }
    }

    private class Parser(val s: String, val limits: Limits) {
        var pos = 0
        var items = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun fail(msg: String): Nothing = throw ParseException(msg, pos)

        fun value(depth: Int): Json {
            if (depth > limits.maxDepth) fail("nesting too deep")
            if (++items > limits.maxItems) fail("too many values")
            skipWs()
            if (pos >= s.length) fail("unexpected end")
            return when (s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> number()
            }
        }

        fun literal(word: String, v: Json): Json {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Json {
            pos++
            val map = LinkedHashMap<String, Json>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return Obj(map) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected key")
                val key = string()
                if (map.containsKey(key)) fail("duplicate key '$key'")
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                map[key] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return Obj(map)
                    else -> { pos--; fail("expected ',' or '}'") }
                }
            }
        }

        fun arr(depth: Int): Json {
            pos++
            val list = ArrayList<Json>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return Arr(list) }
            while (true) {
                list += value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return Arr(list)
                    else -> { pos--; fail("expected ',' or ']'") }
                }
            }
        }

        fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
                if (sb.length > limits.maxStringLength) fail("string too long")
            }
        }

        fun number(): Json {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            if (pos >= s.length || !s[pos].isDigit()) fail("invalid value")
            if (s[pos] == '0') pos++ else while (pos < s.length && s[pos].isDigit()) pos++
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || !s[pos].isDigit()) fail("invalid number")
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || !s[pos].isDigit()) fail("invalid number")
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val d = s.substring(start, pos).toDouble()
            if (d.isInfinite() || d.isNaN()) fail("number out of range")
            return Num(d)
        }
    }
}

private fun quote(sb: StringBuilder, v: String) {
    sb.append('"')
    for (c in v) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}

/** Typed, path-aware accessors that turn malformed documents into readable errors. */
class JsonReader(private val node: Json.Obj, private val path: String = "$") {
    class InvalidException(message: String) : Exception(message)

    private fun err(key: String, msg: String): Nothing = throw InvalidException("$path.$key: $msg")

    fun has(key: String) = node.fields.containsKey(key) && node.fields[key] !is Json.Null

    fun string(key: String): String = (node[key] as? Json.Str)?.value ?: err(key, "expected string")
    fun stringOrNull(key: String): String? = when (val v = node[key]) {
        null, Json.Null -> null
        is Json.Str -> v.value
        else -> err(key, "expected string")
    }

    fun int(key: String): Int {
        val v = (node[key] as? Json.Num)?.value ?: err(key, "expected number")
        if (v != Math.rint(v) || v > Int.MAX_VALUE || v < Int.MIN_VALUE) err(key, "expected integer")
        return v.toInt()
    }

    fun intOrNull(key: String): Int? = if (has(key)) int(key) else null
    fun bool(key: String, default: Boolean): Boolean = when (val v = node[key]) {
        null, Json.Null -> default
        is Json.Bool -> v.value
        else -> err(key, "expected boolean")
    }

    fun obj(key: String): JsonReader = (node[key] as? Json.Obj)?.let { JsonReader(it, "$path.$key") } ?: err(key, "expected object")
    fun objOrNull(key: String): JsonReader? = if (has(key)) obj(key) else null

    fun array(key: String): List<Json> = (node[key] as? Json.Arr)?.items ?: err(key, "expected array")
    fun arrayOrEmpty(key: String): List<Json> = if (has(key)) array(key) else emptyList()

    fun objects(key: String): List<JsonReader> = arrayOrEmpty(key).mapIndexed { i, j ->
        (j as? Json.Obj)?.let { JsonReader(it, "$path.$key[$i]") } ?: err("$key[$i]", "expected object")
    }

    fun strings(key: String): List<String> = arrayOrEmpty(key).mapIndexed { i, j ->
        (j as? Json.Str)?.value ?: err("$key[$i]", "expected string")
    }

    fun ints(key: String): List<Int> = arrayOrEmpty(key).mapIndexed { i, j ->
        val d = (j as? Json.Num)?.value ?: err("$key[$i]", "expected number")
        if (d != Math.rint(d)) err("$key[$i]", "expected integer")
        d.toInt()
    }

    fun stringMap(key: String): Map<String, String> {
        val o = (node[key] as? Json.Obj) ?: return emptyMap()
        return o.fields.mapValues { (k, v) -> (v as? Json.Str)?.value ?: err("$key.$k", "expected string") }
    }

    fun keys(): Set<String> = node.fields.keys

    /** Rejects fields that the schema does not know, so typos never pass silently. */
    fun requireOnly(vararg allowed: String) {
        val extra = node.fields.keys - allowed.toSet()
        if (extra.isNotEmpty()) throw InvalidException("$path: unknown field(s) ${extra.sorted().joinToString()}")
    }

    fun fail(msg: String): Nothing = throw InvalidException("$path: $msg")

    companion object {
        fun parse(text: String): JsonReader {
            val j = Json.parse(text)
            return JsonReader(j as? Json.Obj ?: throw InvalidException("$: expected object"))
        }
    }
}
