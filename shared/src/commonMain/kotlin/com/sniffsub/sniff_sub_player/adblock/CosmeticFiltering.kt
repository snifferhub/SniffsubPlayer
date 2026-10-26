package com.sniffsub.sniff_sub_player.adblock

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Filters are passed as JSON data, never interpolated as executable JavaScript/CSS.
fun cosmeticFilteringScript(selectors: List<String>): String = """
    (() => {
      const selectors = ${Json.encodeToString(selectors)};
      const id = '__sniffsub_adblock_style';
      let style = document.getElementById(id);
      if (!selectors.length) { if (style) style.remove(); return; }
      if (!style) {
        style = document.createElement('style');
        style.id = id;
        (document.head || document.documentElement).appendChild(style);
      }
      const signature = JSON.stringify(selectors);
      if (style.dataset.filters === signature) return;
      style.dataset.filters = signature;
      style.textContent = '';
      const sheet = style.sheet;
      for (const selector of selectors) {
        if (/[{}]/.test(selector)) continue;
        try { sheet.insertRule(selector + ' { display: none !important; }', sheet.cssRules.length); }
        catch (_) {} // One unsupported selector must not invalidate the rest.
      }
    })();
""".trimIndent()
