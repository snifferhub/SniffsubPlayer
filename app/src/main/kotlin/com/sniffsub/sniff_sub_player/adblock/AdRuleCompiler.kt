package com.sniffsub.sniff_sub_player.adblock

class AdRuleCompiler(
    private val maximumRules: Int = 15_000,
) {
    fun compile(lines: Sequence<String>): CompiledRuleSet {
        val patterns = mutableListOf<String>()
        val seenPatterns = mutableSetOf<String>()
        var sourceCount = 0
        var discardedCount = 0

        for (raw in lines) {
            sourceCount += 1
            if (patterns.size >= maximumRules) {
                discardedCount += 1
                continue
            }

            val rule = raw.trim()
            if (rule.isBlank() || rule.startsWith('!') || rule.startsWith('[')) continue

            if (
                rule.startsWith("@@") ||
                rule.contains("##") ||
                rule.contains("#@#") ||
                rule.contains('$')
            ) {
                discardedCount += 1
                continue
            }

            val pattern = convert(rule)
            if (pattern == null || !seenPatterns.add(pattern)) {
                discardedCount += 1
                continue
            }
            patterns += pattern
        }

        return CompiledRuleSet(
            patterns = patterns,
            sourceCount = sourceCount,
            convertedCount = patterns.size,
            discardedCount = discardedCount,
        )
    }

    private fun convert(rule: String): String? {
        if (rule.length > MAXIMUM_RULE_LENGTH) return null

        if (rule.startsWith('/') && rule.endsWith('/') && rule.length > 2) {
            return rule.substring(1, rule.length - 1).takeIf(::isValidRegex)
        }

        if (rule.startsWith("||")) {
            val domain = rule.substring(2)
                .substringBefore('^')
                .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            if (domain.isBlank()) return null

            return "^https?://([^/]+\\.)?${escapeLiteral(domain)}([/:]|$)"
        }

        val normalized = rule.removePrefix("|")
        if (normalized.isBlank()) return null
        return normalized.asSequence().joinToString(separator = "") { character ->
            when (character) {
                '*' -> ".*"
                '^' -> "[^a-zA-Z0-9_.%-]"
                else -> escapeLiteral(character.toString())
            }
        }
    }

    private fun escapeLiteral(value: String): String = value.asSequence().joinToString(separator = "") { character ->
        if (character in REGEX_SPECIAL_CHARACTERS) "\\$character" else character.toString()
    }

    private fun isValidRegex(pattern: String): Boolean = runCatching {
        Regex(pattern)
    }.isSuccess

    private companion object {
        const val MAXIMUM_RULE_LENGTH = 500
        const val REGEX_SPECIAL_CHARACTERS = ".\\+?()[]{}|\$"
    }
}
