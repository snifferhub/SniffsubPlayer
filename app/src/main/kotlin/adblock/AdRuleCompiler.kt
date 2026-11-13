package com.sniffsub.sniff_sub_player.adblock

class AdRuleCompiler(private val maximumRules: Int = 200_000) {
    fun compile(lines: Sequence<String>): CompiledRuleSet {
        val network = mutableListOf<NetworkRule>()
        val cosmetic = mutableListOf<CosmeticRule>()
        val seen = mutableSetOf<String>()
        var sourceCount = 0
        var discarded = 0
        for (raw in lines) {
            sourceCount++
            val rule = raw.trim()
            if (rule.isBlank() || rule.startsWith('!') || rule.startsWith('[')) continue
            if (rule.length > 4_096 || !seen.add(rule) || network.size + cosmetic.size >= maximumRules) {
                discarded++
                continue
            }
            val separator = when {
                rule.contains("#@#") -> "#@#"
                rule.contains("##") -> "##"
                else -> null
            }
            if (separator != null) {
                val selector = rule.substringAfter(separator).trim()
                // Extended CSS and scriptlets require a separate engine. Never execute filter code.
                if (selector.isEmpty() || selector.startsWith('+') ||
                    selector.contains(Regex(":(?:has-text|contains|matches-css|xpath|remove)\\(")) ||
                    selector.any { it == '{' || it == '}' || it == '\n' }
                ) {
                    discarded++
                } else {
                    cosmetic += CosmeticRule(selector, domains(rule.substringBefore(separator), ','), separator == "#@#")
                }
                continue
            }
            if (rule.contains('#')) { discarded++; continue }
            val parsed = parseNetwork(rule)
            if (parsed == null) discarded++ else network += parsed
        }
        // Keep legacy simple patterns available for migration and diagnostics.
        val patterns = network.filter { it.isSimpleBlock }.map { it.pattern }
        return CompiledRuleSet(patterns, sourceCount, network.size + cosmetic.size, discarded, network, cosmetic)
    }

    private fun parseNetwork(raw: String): NetworkRule? {
        val exception = raw.startsWith("@@")
        val body = raw.removePrefix("@@")
        // A regex can contain '$'; modifiers follow its closing slash.
        val optionIndex = if (body.startsWith('/')) {
            body.lastIndexOf('/').let { end -> if (end > 0 && body.getOrNull(end + 1) == '$') end + 1 else -1 }
        } else body.indexOf('$')
        val filter = if (optionIndex >= 0) body.substring(0, optionIndex) else body
        val options = if (optionIndex >= 0) body.substring(optionIndex + 1).split(',') else emptyList()
        var domainList = emptyList<String>()
        val includeTypes = mutableSetOf<ResourceType>()
        val excludeTypes = mutableSetOf<ResourceType>()
        var thirdParty: Boolean? = null
        var matchCase = false
        var important = false
        var elemhide = false
        var generichide = false
        for (option in options) {
            val negated = option.startsWith('~')
            val name = option.removePrefix("~")
            when {
                name.startsWith("domain=") && !negated -> {
                    domainList = domains(name.substringAfter('='), '|')
                    if (domainList.isEmpty() || domainList.any { !DOMAIN.matches(it.removePrefix("~")) }) return null
                }
                name == "third-party" || name == "3p" -> thirdParty = !negated
                name == "first-party" || name == "1p" -> thirdParty = negated
                name == "match-case" && !negated -> matchCase = true
                name == "important" && !negated -> important = true
                name == "elemhide" && exception && !negated -> elemhide = true
                name == "generichide" && exception && !negated -> generichide = true
                ResourceType.fromOption(name) != null -> {
                    val type = requireNotNull(ResourceType.fromOption(name))
                    if (negated) excludeTypes += type else includeTypes += type
                }
                else -> return null // Do not broaden rules with unsupported modifiers.
            }
        }
        val pattern = convert(filter) ?: return null
        if (runCatching { Regex(pattern, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.isFailure) return null
        val hostKey = if (filter.startsWith("||")) {
            filter.removePrefix("||").takeWhile { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                .lowercase().takeIf { DOMAIN.matches(it) && filter.removePrefix("||").drop(it.length).firstOrNull() in listOf('^', '/', ':') }
        } else null
        return NetworkRule(pattern, exception, domainList, includeTypes, excludeTypes, thirdParty,
            matchCase, important, elemhide, generichide, hostKey)
    }

    private fun convert(rule: String): String? {
        if (rule.isBlank()) return null
        if (rule.startsWith('/') && rule.endsWith('/') && rule.length > 2) return rule.substring(1, rule.length - 1)
        val domainAnchor = rule.startsWith("||")
        val startAnchor = !domainAnchor && rule.startsWith('|')
        val endAnchor = rule.endsWith('|')
        val body = rule.removePrefix(if (domainAnchor) "||" else if (startAnchor) "|" else "")
            .let { if (endAnchor) it.dropLast(1) else it }
        if (body.isEmpty()) return null
        val result = buildString {
            if (domainAnchor) append("^https?://(?:[a-z0-9_-]+\\.)*") else if (startAnchor) append('^')
            body.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> append(Regex.escape(character.toString()))
                }
            }
            if (endAnchor) append('$')
        }
        return result
    }

    private fun domains(value: String, separator: Char) = value.split(separator).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private companion object {
        val DOMAIN = Regex("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")
    }
}
