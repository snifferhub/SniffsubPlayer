package com.sniffsub.sniff_sub_player.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRuleCompilerTest {
    @Test
    fun unsupported_rules_are_discarded_while_metadata_is_ignored() {
        val rules = AdRuleCompiler().compile(
            sequenceOf(
                "! comment",
                "[Adblock Plus 2.0]",
                "   ",
                "@@||allow.example^",
                "example##.ad",
                "example#@#.ad",
                "track\$script",
                "/[/",
                "||ads.example^",
                "||ads.example^",
            ),
        )

        assertEquals(10, rules.sourceCount)
        assertEquals(1, rules.convertedCount)
        assertEquals(6, rules.discardedCount)
    }

    @Test
    fun domain_rule_honors_host_boundary() {
        val rules = AdRuleCompiler().compile(sequenceOf("||ads.example^"))

        assertTrue(rules.blocks("https://sub.ads.example/banner"))
        assertTrue(rules.blocks("https://ads.example?campaign=1"))
        assertTrue(rules.blocks("https://ads.example#sponsor"))
        assertTrue(rules.blocks("https://ads.example:8443/banner"))
        assertFalse(rules.blocks("https://badads.example/banner"))
        assertFalse(rules.blocks("https://good.example/?next=ads.example"))
        assertFalse(rules.blocks("https://good.example?next=sub.ads.example/banner"))
        assertFalse(rules.blocks("https://good.example#https://ads.example/banner"))
        assertFalse(rules.blocks("https://ads.example.evil/banner"))
        assertFalse(rules.blocks("https://ads.example:invalid/banner"))
    }

    @Test
    fun complex_domain_rules_are_discarded_instead_of_broadened() {
        val rules = AdRuleCompiler().compile(
            sequenceOf(
                "||example.com^/ads",
                "||example.com/banner*",
                "||valid.example^",
            ),
        )

        assertEquals(1, rules.convertedCount)
        assertEquals(2, rules.discardedCount)
        assertTrue(rules.blocks("https://valid.example/banner"))
        assertFalse(rules.blocks("https://example.com/home"))
    }

    @Test
    fun wildcard_boundary_and_single_anchor_are_translated() {
        val rules = AdRuleCompiler().compile(sequenceOf("|https://cdn.example/banner*^image"))

        assertTrue(rules.blocks("https://cdn.example/banner-large/image"))
        assertFalse(rules.blocks("http://cdn.example/banner-large/image"))
        assertFalse(rules.blocks("https://cdn.example/banner-largeXimage"))
    }

    @Test
    fun valid_regular_expression_is_retained_and_invalid_one_is_discarded() {
        val rules = AdRuleCompiler().compile(sequenceOf("/ads\\.(example|test)\\//", "/[/"))

        assertTrue(rules.blocks("https://ads.example/banner"))
        assertTrue(rules.blocks("https://ads.test/banner"))
        assertFalse(rules.blocks("https://ads.invalid/banner"))
        assertEquals(1, rules.convertedCount)
        assertEquals(1, rules.discardedCount)
    }

    @Test
    fun duplicate_and_overlong_rules_are_discarded() {
        val overlong = "a".repeat(501)
        val rules = AdRuleCompiler().compile(sequenceOf("ads.example", "ads.example", overlong))

        assertEquals(1, rules.convertedCount)
        assertEquals(2, rules.discardedCount)
        assertTrue(rules.blocks("https://ads.example/banner"))
    }

    @Test
    fun rule_limit_keeps_first_fifteen_thousand_unique_rules() {
        val rules = AdRuleCompiler().compile(
            (0..15_000).asSequence().map { "ad$it.example" },
        )

        assertEquals(15_001, rules.sourceCount)
        assertEquals(15_000, rules.convertedCount)
        assertEquals(1, rules.discardedCount)
        assertTrue(rules.blocks("https://ad14999.example/banner"))
        assertFalse(rules.blocks("https://ad15000.example/banner"))
    }
}
