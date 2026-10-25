package ai.opencode.feature.transcript.markdown

/**
 * Lightweight regex-free tokenizer for fenced code blocks (iOS `Media/SyntaxHighlighter.swift`).
 * Not a full grammar: keywords, strings, comments, numbers, and capitalized type names cover
 * the readability win. Produces [SyntaxToken]s (UTF-16 ranges of the source); renderers map
 * each [SyntaxTokenKind] to `OpenCodeTheme.colors.syntax*`, so cached tokens stay right when
 * the appearance changes. Budgets are UTF-8 bytes, as on iOS.
 */
object SyntaxHighlighter {
    /** Lines longer than this render plain so minified JS/JSON, SVG paths, or base64 blobs never stall token scanning during streaming or file open. */
    const val maxHighlightedLineUTF8Bytes = 1_000

    /** Overall byte budget per highlight call, shared by code blocks and the file viewer. */
    const val maxHighlightedUTF8Bytes = 128 * 1024

    /**
     * [tokens] colour the whole highlighted range; the safe prefix (through the last newline
     * outside any token) can be reused as the source grows.
     */
    class Result(
        val tokens: List<SyntaxToken>,
        val safePrefixUTF8Count: Int,
        /** The safe prefix's length in UTF-16 chars. */
        val safePrefixLength: Int,
        val safePrefixTokens: List<SyntaxToken>,
    )

    fun highlight(code: String, language: String?, maxUTF8Bytes: Int = maxHighlightedUTF8Bytes): List<SyntaxToken> =
        highlightWithSafePrefix(code, language, maxUTF8Bytes, computeSafePrefix = false).tokens

    fun highlightIncremental(
        code: String,
        language: String?,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
        reusing: Result? = null,
        reusingSource: String? = null,
    ): Result {
        if (reusing != null && reusingSource != null &&
            reusing.safePrefixUTF8Count > 0 &&
            code.length >= reusing.safePrefixLength &&
            code.regionMatches(0, reusingSource, 0, reusing.safePrefixLength)
        ) {
            val split = reusing.safePrefixLength
            val suffix = code.substring(split)
            val remainingBudget = maxOf(0, maxUTF8Bytes - reusing.safePrefixUTF8Count)
            val suffixResult = highlightWithSafePrefix(suffix, language, remainingBudget, computeSafePrefix = remainingBudget > 0)
            val tokens = ArrayList<SyntaxToken>(reusing.safePrefixTokens.size + suffixResult.tokens.size)
            tokens.addAll(reusing.safePrefixTokens)
            suffixResult.tokens.mapTo(tokens) { SyntaxToken(it.start + split, it.end + split, it.kind) }
            val safeTokens = if (suffixResult.safePrefixUTF8Count > 0) {
                reusing.safePrefixTokens + suffixResult.safePrefixTokens.map { SyntaxToken(it.start + split, it.end + split, it.kind) }
            } else {
                reusing.safePrefixTokens
            }
            return Result(
                tokens = tokens,
                safePrefixUTF8Count = reusing.safePrefixUTF8Count + suffixResult.safePrefixUTF8Count,
                safePrefixLength = split + suffixResult.safePrefixLength,
                safePrefixTokens = safeTokens,
            )
        }
        return highlightWithSafePrefix(code, language, maxUTF8Bytes, computeSafePrefix = true)
    }

    fun highlightWithSafePrefix(
        code: String,
        language: String?,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
        computeSafePrefix: Boolean = true,
    ): Result {
        if (code.isEmpty() || maxUTF8Bytes <= 0) return Result(emptyList(), 0, 0, emptyList())
        val endLimit = highlightEndIndex(code, maxUTF8Bytes)
        val tokens = ArrayList<SyntaxToken>()
        var lastSafeNewlineEnd = -1
        scanTokens(code, endLimit, Language.of(language), onToken = { start, end, kind ->
            if (end > start) tokens.add(SyntaxToken(start, end, kind))
        }, onSafeNewline = { lastSafeNewlineEnd = it })
        if (computeSafePrefix && lastSafeNewlineEnd >= 0) {
            val safeEnd = lastSafeNewlineEnd
            var safeCount = tokens.size
            while (safeCount > 0 && tokens[safeCount - 1].end > safeEnd) safeCount--
            return Result(tokens, SwiftText.utf8Length(code, 0, safeEnd), safeEnd, tokens.subList(0, safeCount).toList())
        }
        return Result(tokens, 0, 0, emptyList())
    }

    /** Where highlighting stops: within [maxUTF8Bytes], snapped back to a line start within one line's budget. */
    private fun highlightEndIndex(code: String, maxUTF8Bytes: Int): Int {
        if (maxUTF8Bytes <= 0 || code.isEmpty()) return 0
        var bytes = 0
        var rawEnd = -1
        var i = 0
        while (i < code.length) {
            val width = utf8Width(code, i)
            if (bytes + width.first > maxUTF8Bytes) {
                rawEnd = i
                break
            }
            bytes += width.first
            i += width.second
        }
        if (rawEnd < 0) return code.length
        // Look back up to one line's budget for a newline.
        var lookBytes = 0
        var j = rawEnd - 1
        while (j >= 0 && lookBytes < maxHighlightedLineUTF8Bytes) {
            if (code[j] == '\n') return j + 1
            lookBytes += if (code[j].code < 0x80) 1 else if (code[j].code < 0x800) 2 else if (Character.isSurrogate(code[j])) 2 else 3
            j--
        }
        return rawEnd
    }

    /** The UTF-8 width of the code point at [i] and its UTF-16 length. */
    private fun utf8Width(s: String, i: Int): Pair<Int, Int> {
        val c = s[i]
        return when {
            c.code < 0x80 -> 1 to 1
            c.code < 0x800 -> 2 to 1
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> 4 to 2
            else -> 3 to 1
        }
    }

    /** The index after a line that fills more than one line's budget, or null when the line at [start] fits. */
    private fun skipLineIfOverBudget(code: String, start: Int, endLimit: Int): Int? {
        var bytes = 0
        var i = start
        while (i < endLimit && bytes < maxHighlightedLineUTF8Bytes + 1) {
            if (code[i] == '\n') return null
            val width = utf8Width(code, i)
            bytes += width.first
            i += width.second
        }
        if (bytes < maxHighlightedLineUTF8Bytes + 1) return null
        val newline = code.indexOf('\n', i)
        return if (newline < 0 || newline >= endLimit) endLimit else newline + 1
    }

    private inline fun scanTokens(
        code: String,
        endLimit: Int,
        language: Language,
        onToken: (Int, Int, SyntaxTokenKind) -> Unit,
        onSafeNewline: (Int) -> Unit,
    ) {
        var index = 0
        var atLineStart = true
        while (index < endLimit) {
            if (atLineStart) {
                val afterLongLine = skipLineIfOverBudget(code, index, endLimit)
                if (afterLongLine != null) {
                    if (afterLongLine > index && code[afterLongLine - 1] == '\n') onSafeNewline(afterLongLine)
                    index = afterLongLine
                    continue
                }
                atLineStart = false
            }

            val character = code[index]
            val next = if (index + 1 < endLimit) code[index + 1] else null

            // Line comments
            if ((character == '/' && language.slashComments && next == '/') || (character == '#' && language.hashComments)) {
                val end = minOf(lineEnd(code, index), endLimit)
                onToken(index, end, SyntaxTokenKind.Comment)
                index = end
                continue
            }

            // Block comments
            if (character == '/' && language.slashComments && next == '*') {
                var end = minOf(index + 2, endLimit)
                while (end < endLimit) {
                    if (code[end] == '*' && end + 1 < endLimit && code[end + 1] == '/') {
                        end = minOf(end + 2, endLimit)
                        break
                    }
                    end++
                }
                onToken(index, end, SyntaxTokenKind.Comment)
                index = end
                if (index < endLimit) {
                    val afterLongLine = skipLineIfOverBudget(code, index, endLimit)
                    if (afterLongLine != null) {
                        if (afterLongLine > index && code[afterLongLine - 1] == '\n') onSafeNewline(afterLongLine)
                        index = afterLongLine
                        atLineStart = true
                    }
                }
                continue
            }

            // Strings
            if (character == '"' || (character == '\'' && language.singleQuoteStrings)) {
                var end = index + 1
                while (end < endLimit && code[end] != character && code[end] != '\n') {
                    if (code[end] == '\\' && end + 1 < endLimit) end++
                    end++
                }
                if (end < endLimit && code[end] == character) end++
                onToken(index, minOf(end, endLimit), SyntaxTokenKind.String)
                index = minOf(end, endLimit)
                continue
            }

            val cp = Character.codePointAt(code, index)
            val cpLength = Character.charCount(cp)

            // Numbers
            if (isNumber(cp)) {
                var end = index + cpLength
                while (end < endLimit && (isHexDigit(code[end]) || code[end] in "xXoObB._")) end++
                onToken(index, end, SyntaxTokenKind.Number)
                index = end
                continue
            }

            // Identifiers, keywords, and types
            if (Character.isLetter(cp) || character == '_') {
                var end = index + cpLength
                while (end < endLimit) {
                    val c = Character.codePointAt(code, end)
                    if (!(Character.isLetter(c) || isNumber(c) || c == '_'.code || isMark(c))) break
                    end += Character.charCount(c)
                }
                val word = code.substring(index, end)
                if (word in language.keywords) {
                    onToken(index, end, SyntaxTokenKind.Keyword)
                } else if (Character.isUpperCase(cp)) {
                    onToken(index, end, SyntaxTokenKind.Type)
                }
                index = end
                continue
            }

            val after = index + cpLength
            if (character == '\n') {
                onSafeNewline(after)
                atLineStart = true
            }
            index = after
        }
    }

    private fun isNumber(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Combining marks extend the grapheme before them (iOS scans Characters). */
    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' ||
        c in '\uFF10'..'\uFF19' || c in '\uFF21'..'\uFF26' || c in '\uFF41'..'\uFF46'

    private fun lineEnd(code: String, index: Int): Int = code.indexOf('\n', index).let { if (it < 0) code.length else it }

    private class Language(
        val keywords: Set<String>,
        val slashComments: Boolean,
        val hashComments: Boolean,
        val singleQuoteStrings: Boolean,
    ) {
        companion object {
            private val swift = Language(swiftKeywords, slashComments = true, hashComments = false, singleQuoteStrings = false)
            private val js = Language(jsKeywords, slashComments = true, hashComments = false, singleQuoteStrings = true)
            private val python = Language(pythonKeywords, slashComments = false, hashComments = true, singleQuoteStrings = true)
            private val shell = Language(shellKeywords, slashComments = false, hashComments = true, singleQuoteStrings = true)
            private val all = Language(allKeywords, slashComments = true, hashComments = true, singleQuoteStrings = true)

            fun of(hint: String?): Language = when (hint?.lowercase()) {
                "swift" -> swift
                "js", "jsx", "ts", "tsx", "javascript", "typescript" -> js
                "py", "python" -> python
                "sh", "bash", "zsh", "shell", "yaml", "yml", "toml" -> shell
                else -> all
            }
        }
    }

    private val swiftKeywords: Set<String> = setOf(
        "func", "let", "var", "if", "else", "guard", "return", "for", "while", "switch", "case",
        "break", "continue", "struct", "class", "enum", "protocol", "extension", "import", "init",
        "self", "static", "private", "public", "internal", "final", "throws", "async", "await",
        "try", "catch", "do", "defer", "in", "where", "nil", "true", "false", "some", "any",
        "typealias", "inout", "mutating", "override", "default",
    )

    private val jsKeywords: Set<String> = setOf(
        "function", "const", "let", "var", "if", "else", "return", "for", "while", "switch",
        "case", "break", "continue", "class", "extends", "import", "export", "from", "default",
        "new", "this", "static", "async", "await", "try", "catch", "finally", "throw", "typeof",
        "instanceof", "in", "of", "null", "undefined", "true", "false", "interface", "type",
        "enum", "implements", "readonly", "yield",
    )

    private val pythonKeywords: Set<String> = setOf(
        "def", "class", "if", "elif", "else", "return", "for", "while", "break", "continue",
        "import", "from", "as", "with", "try", "except", "finally", "raise", "lambda", "pass",
        "yield", "global", "nonlocal", "in", "is", "not", "and", "or", "None", "True", "False",
        "async", "await", "self",
    )

    /** Unlabelled fences get every general-purpose language's keywords. */
    private val allKeywords: Set<String> = swiftKeywords + jsKeywords + pythonKeywords

    private val shellKeywords: Set<String> = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "local", "export", "return", "echo", "exit", "set", "source",
    )
}
