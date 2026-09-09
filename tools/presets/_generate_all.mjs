// Run from the repository root. Source shards are regenerated here; Gradle writePresets owns bundled resources.
import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { qualitySources, isFood, statsOf } from './_quality_rules.mjs';
import { lore as remainingLore } from './_lore_remaining.mjs';

const root = 'tools/presets';
const legacyPath = join(root, '_legacy_authored.json');
if (!existsSync(legacyPath)) {
  const legacy = {};
  for (const language of ['zh_cn', 'en_us']) {
    legacy[language] = Object.fromEntries(readdirSync(join(root, language)).filter(file => file.endsWith('.json')).sort()
      .map(file => [file, JSON.parse(readFileSync(join(root, language, file), 'utf8'))]));
  }
  writeFileSync(legacyPath, JSON.stringify(legacy, null, 2) + '\n');
}
await import('./_build_tables.mjs');
await import('./_gen_colored.mjs');
await import('./_gen_building.mjs');
const { displayName } = await import('./_lib.mjs');
const categories = JSON.parse(readFileSync(join(root, 'categories.json'), 'utf8'));
const expected = new Set(Object.values(categories).flat());
if (expected.size !== 1218) throw new Error(`expected 1218 survival items, got ${expected.size}`);

function readShards(language) {
  const result = new Map();
  for (const file of readdirSync(join(root, language)).filter(file => file.endsWith('.json')).sort()) {
    const path = join(root, language, file);
    const entries = JSON.parse(readFileSync(path, 'utf8'));
    for (const [id, entry] of Object.entries(entries)) {
      if (!expected.has(id)) throw new Error(`${path} includes non-survival item ${id}`);
      if (result.has(id)) throw new Error(`${path} repeats ${id}`);
      result.set(id, { file, entry });
    }
  }
  return result;
}

let sources = 0;
let variants = 0;
let foods = 0;
let gear = 0;
for (const language of ['zh_cn', 'en_us']) {
  const input = readShards(language);
  const output = new Map();
  for (const [category, ids] of Object.entries(categories)) {
    for (const id of ids) {
      const bare = id.replace('minecraft:', '');
      const existing = input.get(id);
      const copy = remainingLore[bare];
      if (!existing && !copy) throw new Error(`${language} is missing authored lore for ${id}`);
      const base = existing?.entry.base ?? { loreJson: [{ text: copy[language === 'zh_cn' ? 0 : 1], color: 'gray', italic: true }] };
      const loreJson = base.loreJson ?? (base.lore ?? []).map(text => ({ text, color: 'gray', italic: true }));
      if (!loreJson.length || loreJson.some(line => !line.text?.trim())) throw new Error(`${id} has empty lore`);
      const legacyEntry = existing?.entry.base && Object.keys(existing.entry.base).some(key => key !== 'loreJson');
      if (bare === 'iron_sword' && legacyEntry) loreJson.splice(0, loreJson.length, { text: language === 'zh_cn'
        ? '铁质剑身带着冷硬的分量，剑脊厚实，刃口向两侧收薄。'
        : 'The iron blade has a cold, solid weight, its thick spine tapering to both edges.', color: 'gray', italic: true });
      const entry = { base: { loreJson }, sources: qualitySources(bare, language, displayName(language, bare), loreJson.map(line => line.text).join(' ')) };
      // Once migrated, source shards are the editable source of truth. Only missing acquisition pools are generated.
      if (!legacyEntry && existing?.entry.sources) {
        const previous = existing.entry.sources;
        const existingTypes = new Set(previous.map(source => source.type));
        entry.sources = [...previous, ...entry.sources.filter(source => !existingTypes.has(source.type))];
      }
      if (!entry.sources.length) delete entry.sources;
      const file = existing?.file ?? `${category}_remaining.json`;
      if (!output.has(file)) output.set(file, {});
      output.get(file)[id] = entry;
      if (language === 'zh_cn') {
        sources += entry.sources?.length ?? 0;
        variants += (entry.sources ?? []).reduce((sum, source) => sum + source.variants.length, 0);
        if (isFood(bare)) foods++;
        else if (statsOf(bare).durability || statsOf(bare).armor) gear++;
      }
    }
  }
  for (const [file, entries] of output) {
    const sorted = Object.fromEntries(Object.entries(entries).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(join(root, language, file), JSON.stringify(sorted, null, 2) + '\n');
  }
  console.log(`${language}: ${expected.size} items in ${output.size} shards`);
}
console.log(`Quality: ${foods} edible items, ${gear} equipment items, ${sources} source pools, ${variants} variants.`);
