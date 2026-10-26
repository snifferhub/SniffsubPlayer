package com.sniffsub.sniff_sub_player.adblock

import com.google.common.net.InternetDomainName
import java.net.URI

enum class ResourceType {
    DOCUMENT, SUBDOCUMENT, SCRIPT, IMAGE, STYLESHEET, FONT, MEDIA, XMLHTTPREQUEST, OTHER;
    companion object {
        fun fromOption(name: String): ResourceType? = when (name) {
            "document" -> DOCUMENT
            "subdocument" -> SUBDOCUMENT
            "script" -> SCRIPT
            "image" -> IMAGE
            "stylesheet" -> STYLESHEET
            "font" -> FONT
            "media" -> MEDIA
            "xmlhttprequest", "xhr" -> XMLHTTPREQUEST
            "other" -> OTHER
            else -> null
        }
    }
}

data class AdRequest(val url: String, val pageUrl: String = "", val type: ResourceType = ResourceType.OTHER) {
    val host: String = urlHost(url)
    val pageHost: String = urlHost(pageUrl)
    val thirdParty: Boolean? by lazy {
        if (host.isEmpty() || pageHost.isEmpty()) null else site(host) != site(pageHost)
    }
    private fun site(host: String): String = runCatching {
        val domain = InternetDomainName.from(host)
        if (domain.isUnderPublicSuffix) domain.topPrivateDomain().toString() else host
    }.getOrDefault(host)
}

internal fun urlHost(url: String): String = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull().orEmpty()
internal fun domainMatches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")
internal fun appliesToDomain(domains: List<String>, host: String): Boolean {
    if (domains.isEmpty()) return true
    if (host.isEmpty()) return false
    val exclusions = domains.filter { it.startsWith('~') }
    if (exclusions.any { domainMatches(host, it.drop(1)) }) return false
    val inclusions = domains.filterNot { it.startsWith('~') }
    return inclusions.isEmpty() || inclusions.any { domainMatches(host, it) }
}

data class NetworkRule(
    val pattern: String,
    val exception: Boolean = false,
    val domains: List<String> = emptyList(),
    val includeTypes: Set<ResourceType> = emptySet(),
    val excludeTypes: Set<ResourceType> = emptySet(),
    val thirdParty: Boolean? = null,
    val matchCase: Boolean = false,
    val important: Boolean = false,
    val elemhide: Boolean = false,
    val generichide: Boolean = false,
    val hostKey: String? = null,
) {
    private val regex = Regex(pattern, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    val isSimpleBlock get() = !exception && domains.isEmpty() && includeTypes.isEmpty() && excludeTypes.isEmpty() &&
        thirdParty == null && !matchCase && !important && !elemhide && !generichide
    fun matches(request: AdRequest): Boolean =
        (request.type != ResourceType.DOCUMENT || ResourceType.DOCUMENT in includeTypes || elemhide || generichide) && appliesToDomain(domains, request.pageHost) &&
        (includeTypes.isEmpty() || request.type in includeTypes) && request.type !in excludeTypes &&
        (thirdParty == null || request.thirdParty == thirdParty) && regex.containsMatchIn(request.url)
}

data class CosmeticRule(val selector: String, val domains: List<String> = emptyList(), val exception: Boolean = false)

data class CompiledRuleSet(
    val patterns: List<String>,
    val sourceCount: Int,
    val convertedCount: Int,
    val discardedCount: Int,
    val networkRules: List<NetworkRule> = patterns.map { NetworkRule(it, matchCase = true) },
    val cosmeticRules: List<CosmeticRule> = emptyList(),
) {
    private val indexed = networkRules.filter { it.hostKey != null }.groupBy { requireNotNull(it.hostKey) }
    private val general = networkRules.filter { it.hostKey == null }
    private val pageExceptions = networkRules.filter { it.exception && (ResourceType.DOCUMENT in it.includeTypes || it.elemhide || it.generichide) }
    private fun candidates(request: AdRequest): Sequence<NetworkRule> = sequence {
        yieldAll(general)
        var host = request.host
        while (host.isNotEmpty()) {
            yieldAll(indexed[host].orEmpty())
            host = host.substringAfter('.', "")
        }
    }
    fun blocks(url: String): Boolean = blocks(AdRequest(url))
    fun blocks(request: AdRequest): Boolean {
        if (request.host.isEmpty()) return false
        val document = AdRequest(request.pageUrl, request.pageUrl, ResourceType.DOCUMENT)
        if (pageExceptions.any { ResourceType.DOCUMENT in it.includeTypes && it.matches(document) }) return false
        val matches = candidates(request).filter { !it.elemhide && !it.generichide && it.matches(request) }.toList()
        if (matches.any { it.exception && it.important }) return false
        if (matches.any { !it.exception && it.important }) return true
        return matches.any { !it.exception } && matches.none { it.exception }
    }
    fun selectors(pageUrl: String): List<String> {
        val page = AdRequest(pageUrl, pageUrl, ResourceType.DOCUMENT)
        if (page.host.isEmpty()) return emptyList()
        val exceptions = pageExceptions.filter { it.matches(page) }
        if (exceptions.any { it.elemhide || ResourceType.DOCUMENT in it.includeTypes }) return emptyList()
        val genericHidden = exceptions.any { it.generichide }
        val applicable = cosmeticRules.filter { appliesToDomain(it.domains, page.host) }
        val allowed = applicable.filter { it.exception }.map { it.selector }.toSet()
        return applicable.asSequence().filter { !it.exception && it.selector !in allowed && (!genericHidden || it.domains.isNotEmpty()) }
            .map { it.selector }.distinct().toList()
    }
}

