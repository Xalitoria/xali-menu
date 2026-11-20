package com.xalitoria.menu.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** The documentation of one class that is read from a menu file. [fields] are by the name of the Kotlin property. */
data class ClassDocs(val doc: String, val fields: Map<String, FieldDocs>, val supertypes: List<String> = emptyList())

/** The comment and the default of one property, as written in the source. */
data class FieldDocs(val doc: String, val default: String?)

/**
 * Reads the KDoc comments and defaults of the model out of Kotlin source files.
 *
 * The serializer descriptors know the shape of a class but not its comments or the defaults of its properties, and the
 * editor shows both. The sources of the model are regular enough (one declaration per line, the properties in the
 * primary constructor) that a small scanner is enough, and it keeps the comments the only place the text is written.
 * Classes are found by their serial name: the `@SerialName` if there is one, otherwise the qualified name.
 */
class SourceDocs(private val classes: Map<String, List<ClassDocs>>) {
    /** The docs of the class called [serialName]. Several classes can share a serial name (an action and a condition); [base] picks the one that extends it. */
    fun find(serialName: String, base: String? = null): ClassDocs? =
        classes[serialName]?.let { candidates -> if (base == null) candidates.firstOrNull() else candidates.firstOrNull { base in it.supertypes } }

    companion object {
        private val DECLARATION = Regex("""\b(class|interface|object)\s+(\w+)""")
        private val ANNOTATION = Regex("""@\w+(\([^)]*\))?""")
        private val MODIFIERS = setOf("data", "sealed", "enum", "abstract", "open", "internal", "private", "public", "fun", "annotation", "value")
        private val SERIAL_NAME = Regex("""@SerialName\("([^"]+)"\)""")

        /** Scans every `.kt` file under [roots]. */
        fun scan(vararg roots: Path): SourceDocs {
            val found = mutableMapOf<String, MutableList<ClassDocs>>()
            for (root in roots) {
                Files.walk(root).use { paths ->
                    paths.filter { it.extension == "kt" }.forEach { file ->
                        scanFile(Files.readString(file)).forEach { entry -> found.getOrPut(entry.first) { mutableListOf() } += entry.second }
                    }
                }
            }
            return SourceDocs(found)
        }

        internal fun scanFile(text: String): List<Pair<String, ClassDocs>> {
            val lines = text.split('\n')
            val pkg = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()
            val result = mutableListOf<Pair<String, ClassDocs>>()
            // The classes that enclose the line being read, with the indent they were declared at.
            val stack = ArrayDeque<Pair<String, Int>>()
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val match = DECLARATION.find(line)
                val before = match?.let { line.substring(0, it.range.first).replace(ANNOTATION, " ").trim() }
                val isDeclaration = match != null && before!!.split(Regex("""\s+""")).all { it.isEmpty() || it in MODIFIERS } &&
                    line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                if (!isDeclaration) { index++; continue }
                val indent = line.length - line.trimStart().length
                while (stack.isNotEmpty() && stack.last().second >= indent) stack.removeLast()
                val name = match.groupValues[2]
                stack.addLast(name to indent)

                val [doc, annotations] = leadingComment(lines, index)
                val serialName = SERIAL_NAME.find(line)?.groupValues?.get(1) ?: SERIAL_NAME.find(annotations)?.groupValues?.get(1)
                    ?: (listOf(pkg) + stack.map { it.first }).filter { it.isNotEmpty() }.joinToString(".")

                // The parameters of the primary constructor, from the opening parenthesis to its match.
                val open = line.indexOf('(', match.range.last)
                var rest = line.substring(match.range.last)
                val fields = if (open >= 0) {
                    val [body, end, tail] = balanced(lines, index, open)
                    index = end
                    rest = tail
                    parameters(body)
                } else emptyMap()
                result += serialName to ClassDocs(doc, fields, supertypes(rest))
                index++
            }
            return result
        }

        /** The KDoc directly above line [at] (annotation lines in between are skipped), and the text of those annotations. */
        private fun leadingComment(lines: List<String>, at: Int): Pair<String, String> {
            var i = at - 1
            val annotations = StringBuilder()
            while (i >= 0 && lines[i].trim().startsWith("@")) { annotations.append(lines[i]).append(' '); i-- }
            if (i < 0 || !lines[i].trim().endsWith("*/")) return "" to annotations.toString()
            var start = i
            while (start >= 0 && !lines[start].contains("/**")) start--
            if (start < 0) return "" to annotations.toString()
            return clean(lines.subList(start, i + 1).joinToString("\n")) to annotations.toString()
        }

        /** The names after the colon of a declaration: `) : Action, Other<T> {` gives `Action` and `Other`. */
        private fun supertypes(rest: String): List<String> {
            val colon = rest.indexOf(':').takeIf { it >= 0 } ?: return emptyList()
            return rest.substring(colon + 1).substringBefore('{').split(',').map { it.trim().substringBefore('<').substringBefore('(').trim() }.filter { it.isNotEmpty() }
        }

        /** The text between the parenthesis at [column] of line [from] and its match, the line the match is on, and what follows the match on that line. */
        private fun balanced(lines: List<String>, from: Int, column: Int): Triple<String, Int, String> {
            val body = StringBuilder()
            var depth = 0
            var inString = false
            var lineIndex = from
            var col = column
            while (lineIndex < lines.size) {
                val text = lines[lineIndex]
                while (col < text.length) {
                    val c = text[col]
                    if (inString) {
                        if (c == '\\') { body.append(c); col++; if (col < text.length) body.append(text[col]) }
                        else { body.append(c); if (c == '"') inString = false }
                    } else when (c) {
                        '"' -> { inString = true; body.append(c) }
                        '(' -> { depth++; if (depth > 1) body.append(c) }
                        ')' -> { depth--; if (depth == 0) return Triple(body.toString(), lineIndex, text.substring(col + 1)) else body.append(c) }
                        else -> body.append(c)
                    }
                    col++
                }
                body.append('\n')
                lineIndex++
                col = 0
            }
            return Triple(body.toString(), lines.lastIndex, "")
        }

        /** Splits a parameter list at the commas outside brackets and strings, and reads each parameter. */
        private fun parameters(body: String): Map<String, FieldDocs> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inString = false
            var inComment = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    inComment -> { current.append(c); if (c == '*' && body.getOrNull(i + 1) == '/') { current.append('/'); i++; inComment = false } }
                    inString -> { current.append(c); if (c == '\\') { i++; current.append(body.getOrNull(i) ?: ' ') } else if (c == '"') inString = false }
                    c == '/' && body.getOrNull(i + 1) == '*' -> { inComment = true; current.append(c) }
                    c == '"' -> { inString = true; current.append(c) }
                    c in "([{<" -> { depth++; current.append(c) }
                    c in ")]}" -> { depth--; current.append(c) }
                    c == '>' && body.getOrNull(i - 1) != '-' -> { depth--; current.append(c) }
                    c == ',' && depth == 0 -> { parts += current.toString(); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotBlank()) parts += current.toString()

            val result = mutableMapOf<String, FieldDocs>()
            for (part in parts) {
                val comment = Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).find(part)
                val rest = part.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
                val declaration = Regex("""\b(?:val|var)\s+(\w+)\s*:\s*(.+?)(?:=(.*))?$""", RegexOption.DOT_MATCHES_ALL).find(rest) ?: continue
                result[declaration.groupValues[1]] = FieldDocs(
                    doc = comment?.let { clean(it.value) }.orEmpty(),
                    default = declaration.groupValues[3].trim().ifEmpty { null },
                )
            }
            return result
        }

        /** The text of a KDoc block: no stars, one line per paragraph, and `[Name]` links turned into code. */
        internal fun clean(block: String): String {
            val lines = block.lines().map { it.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim() }
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            for (line in lines) {
                if (line.isEmpty()) {
                    if (current.isNotEmpty()) { paragraphs += current.toString(); current.clear() }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line)
                }
            }
            if (current.isNotEmpty()) paragraphs += current.toString()
            return paragraphs.joinToString("\n\n") { link.replace(it) { m -> "`${m.groupValues[1]}`" } }.replace(Regex("""\s+```"""), "```")
        }

        private val link = Regex("""(?<!`)\[([A-Za-z_][\w.]*)](?!\()""")
    }
}
