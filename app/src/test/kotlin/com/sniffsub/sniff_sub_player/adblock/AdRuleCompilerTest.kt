package com.sniffsub.sniff_sub_player.adblock

import org.junit.Assert.*
import org.junit.Test

class AdRuleCompilerTest {
    private fun compile(vararg rules: String) = AdRuleCompiler().compile(rules.asSequence())
    private fun request(url: String, page: String = "https://news.example/article", type: ResourceType = ResourceType.SCRIPT) = AdRequest(url, page, type)

    @Test fun domain_rule_honors_host_boundary_and_ports() {
        val rules = compile("||ads.example^")
        listOf("https://sub.ads.example/banner", "https://ads.example?campaign=1", "https://ads.example#sponsor", "https://ads.example:8443/banner", "https://ads.example").forEach { assertTrue(it, rules.blocks(it)) }
        listOf("https://badads.example/banner", "https://good.example/?next=ads.example", "https://ads.example.evil/banner", "https://ads.example:invalid/banner").forEach { assertFalse(it, rules.blocks(it)) }
    }

    @Test fun domain_path_and_wildcards_do_not_block_unrelated_paths() {
        val rules = compile("||example.com/banner*", "||ads*.example/promoted^")
        assertTrue(rules.blocks("https://sub.example.com/banner-large.png"))
        assertFalse(rules.blocks("https://example.com/home"))
        assertTrue(rules.blocks("https://ads2.example/promoted?a=1"))
    }

    @Test fun start_and_end_anchors_are_respected() {
        val rules = compile("|https://cdn.example/banner*^image|")
        assertTrue(rules.blocks("https://cdn.example/banner-large/image"))
        assertFalse(rules.blocks("https://site.test/?url=https://cdn.example/banner-large/image"))
        assertFalse(rules.blocks("https://cdn.example/banner-large/image/other"))
        assertFalse(rules.blocks("https://cdn.example/banner-largeXimage"))
    }

    @Test fun exceptions_override_blocks_and_important_precedence_is_respected() {
        val rules = compile("||cdn.example^", "@@||cdn.example/player.js")
        assertFalse(rules.blocks(request("https://cdn.example/player.js")))
        assertTrue(rules.blocks(request("https://cdn.example/ad.js")))
        assertTrue(compile("||cdn.example^\$important", "@@||cdn.example^").blocks(request("https://cdn.example/ad.js")))
        assertFalse(compile("||cdn.example^\$important", "@@||cdn.example^\$important").blocks(request("https://cdn.example/ad.js")))
    }

    @Test fun domain_type_and_third_party_conditions_are_not_broadened() {
        val rules = compile("||cdn.example^\$script,third-party,domain=news.example|~safe.news.example")
        assertTrue(rules.blocks(request("https://cdn.example/ad.js")))
        assertFalse(rules.blocks(request("https://cdn.example/ad.png", type = ResourceType.IMAGE)))
        assertFalse(rules.blocks(request("https://cdn.example/ad.js", "https://safe.news.example")))
        assertFalse(rules.blocks(request("https://cdn.example/ad.js", "https://other.example")))
        assertFalse(rules.blocks(request("https://cdn.example/ad.js", "")))
    }

    @Test fun third_party_uses_public_suffixes_including_co_uk_and_private_hosts() {
        assertFalse(requireNotNull(AdRequest("https://cdn.example.co.uk/a", "https://www.example.co.uk").thirdParty))
        assertTrue(requireNotNull(AdRequest("https://other.co.uk/a", "https://example.co.uk").thirdParty))
        assertTrue(requireNotNull(AdRequest("https://one.github.io/a", "https://two.github.io").thirdParty))
    }

    @Test fun negative_types_match_case_and_regex_modifiers_work() {
        val rules = compile("/Banner[0-9]+$/\$script,match-case", "||ads.example^\$~media")
        assertTrue(rules.blocks(request("https://cdn.example/Banner12")))
        assertFalse(rules.blocks(request("https://cdn.example/banner12")))
        assertFalse(rules.blocks(request("https://ads.example/video.mp4", type = ResourceType.MEDIA)))
        assertTrue(rules.blocks(request("https://ads.example/ad.js")))
    }

    @Test fun cosmetic_domain_rules_and_exceptions_select_only_matching_sites() {
        val rules = compile("##.advert", "news.example,~safe.news.example##.sponsor", "news.example#@#.advert", "news.example##+js(set, ad, false)")
        assertEquals(listOf(".sponsor"), rules.selectors("https://news.example"))
        assertEquals(emptyList<String>(), rules.selectors("https://safe.news.example"))
        assertEquals(listOf(".advert"), rules.selectors("https://other.example"))
        assertEquals(1, rules.discardedCount)
    }

    @Test fun document_exceptions_disable_network_and_cosmetic_filtering() {
        val rules = compile("||ads.example^", "##.ad", "@@||news.example^\$document")
        assertFalse(rules.blocks(request("https://ads.example/ad.js")))
        assertEquals(emptyList<String>(), rules.selectors("https://news.example"))
        assertTrue(rules.blocks(request("https://ads.example/ad.js", "https://other.example")))
        assertFalse(rules.blocks(request("https://ads.example", type = ResourceType.DOCUMENT)))
    }

    @Test fun cosmetic_only_exceptions_do_not_allow_network_ads() {
        val rules = compile("||ads.example^", "##.ad", "news.example##.specific", "@@||news.example^\$generichide")
        assertEquals(listOf(".specific"), rules.selectors("https://news.example"))
        assertTrue(rules.blocks(request("https://ads.example/ad.js")))
        assertEquals(emptyList<String>(), compile("##.ad", "@@||news.example^\$elemhide").selectors("https://news.example"))
    }

    @Test fun unsupported_modifiers_invalid_regex_and_scriptlets_are_discarded() {
        val rules = compile("! metadata", "[Adblock Plus 2.0]", "||ads.example^\$redirect=noopjs", "/[/", "example#%#alert(1)", "example##.ad { color: red }", "||valid.example^")
        assertEquals(1, rules.convertedCount)
        assertEquals(4, rules.discardedCount)
        assertFalse(rules.blocks("https://ads.example/ad.js"))
    }

    @Test fun rules_after_old_fifteen_thousand_limit_are_retained() {
        val rules = AdRuleCompiler().compile((0..15_000).asSequence().map { "||ad$it.example^" })
        assertEquals(15_001, rules.convertedCount)
        assertTrue(rules.blocks("https://ad15000.example/banner"))
    }

    @Test fun jable_page_rules_hide_banners_without_blocking_video_or_normal_images() {
        // Representative rules from the built-in EasyList China subscription.
        val rules = compile(
            "||jable.tv/assets/*0.gif",
            "||jable.tv/assets/images/*-slider.",
            "jable.tv###jimds-slider",
            "jable.tv##div[class^=\"root-\"]",
        )
        assertEquals(4, rules.convertedCount)
        assertEquals(0, rules.discardedCount)
        assertEquals(listOf("#jimds-slider", "div[class^=\"root-\"]"), rules.selectors("https://jable.tv/videos/example/"))
        assertTrue(rules.blocks(AdRequest("https://jable.tv/assets/images/promo-slider.jpg", "https://jable.tv", ResourceType.IMAGE)))
        assertTrue(rules.blocks(AdRequest("https://jable.tv/assets/banner0.gif", "https://jable.tv", ResourceType.IMAGE)))
        assertFalse(rules.blocks(AdRequest("https://jable.tv/assets/images/poster.jpg", "https://jable.tv", ResourceType.IMAGE)))
        assertFalse(rules.blocks(AdRequest("https://media.example/movie.m3u8", "https://jable.tv", ResourceType.MEDIA)))
        assertEquals(emptyList<String>(), rules.selectors("https://other.example"))
    }

    @Test fun duplicates_and_configured_limits_are_counted() {
        val rules = AdRuleCompiler(2).compile(sequenceOf("||one.example^", "||one.example^", "##.ad", "||two.example^"))
        assertEquals(2, rules.convertedCount)
        assertEquals(2, rules.discardedCount)
    }
}
