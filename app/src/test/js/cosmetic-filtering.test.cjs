const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const kotlin = readFileSync('shared/src/commonMain/kotlin/com/sniffsub/sniff_sub_player/adblock/CosmeticFiltering.kt', 'utf8');
const template = kotlin.split('"""')[1];
function environment() {
  let style = null;
  let insertions = 0;
  const document = {
    getElementById: () => style,
    head: { appendChild: item => { style = item; } },
    createElement: () => ({
      dataset: {},
      set textContent(value) { this.sheet.cssRules.length = 0; },
      sheet: {
        cssRules: [],
        insertRule(rule) {
          if (rule.startsWith(':unsupported')) throw Error('Unsupported selector');
          this.cssRules.push(rule);
          insertions++;
        },
      },
      remove() { style = null; },
    }),
  };
  const context = vm.createContext({ document });
  return {
    apply(selectors) {
      vm.runInContext(template.replace('${Json.encodeToString(selectors)}', JSON.stringify(selectors)), context);
    },
    rules: () => style?.sheet.cssRules ?? [],
    insertions: () => insertions,
    exists: () => style !== null,
  };
}
test('invalid selector does not prevent valid rules and repeated updates do not reinsert CSS', () => {
  const page = environment();
  page.apply(['.advert', ':unsupported(foo)', '.sponsor']);
  assert.equal(page.rules().length, 2);
  page.apply(['.advert', ':unsupported(foo)', '.sponsor']);
  assert.equal(page.insertions(), 2);
});
test('disabling restores elements by removing CSS and reenabling reapplies it', () => {
  const page = environment();
  page.apply(['.advert']);
  page.apply([]);
  assert.equal(page.exists(), false);
  page.apply(['.sponsor']);
  assert.equal(page.rules().length, 1);
});
test('filter text stays data and cannot insert declarations or execute JavaScript', () => {
  const page = environment();
  page.apply(['.advert', '.ad { display: block }', '\"; throw Error(\"injected\"); //']);
  assert.equal(page.rules().some(rule => rule.includes('{ display: block }')), false);
});
