import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => JSON.parse(readFileSync(path, 'utf8'));
const shards = (directory, language) => Object.assign({}, ...readdirSync(join(directory, 'tools/presets', language))
  .filter(file => file.endsWith('.json')).map(file => read(join(directory, 'tools/presets', language, file))));
const withoutCopy = value => Array.isArray(value) ? value.map(withoutCopy)
  : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value)
    .filter(([key]) => !['loreJson', 'itemName'].includes(key)).map(([key, nested]) => [key, withoutCopy(nested)])) : value;

test('survival coverage, physical descriptions and bilingual gameplay remain complete', () => {
  const categories = read(join(root, 'tools/presets/categories.json'));
  const expected = new Set(Object.values(categories).flat());
  const exclusions = read(join(root, 'tools/presets/exclusions.json'));
  assert.equal(expected.size, 1218);
  assert.match(exclusions['minecraft:player_head'], /原版生存/);
  assert.match(exclusions['minecraft:bundle'], /1.21.1.*实验/);
  const chinese = shards(root, 'zh_cn');
  const english = shards(root, 'en_us');
  assert.deepEqual(withoutCopy(chinese), withoutCopy(english));
  for (const [language, items] of [['zh_cn', chinese], ['en_us', english]]) {
    assert.deepEqual(new Set(Object.keys(items)), expected);
    const copy = new Map();
    for (const [id, entry] of Object.entries(items)) {
      assert.deepEqual(Object.keys(entry.base), ['loreJson'], id);
      const text = entry.base.loreJson.map(line => line.text).join(' ');
      assert.ok(text.trim(), id);
      assert.ok(!copy.has(text), `${language}: ${id} repeats ${copy.get(text)}`);
      copy.set(text, id);
    }
  }
  for (const id of ['sweet_berries', 'glow_berries', 'honey_bottle', 'mushroom_stew', 'suspicious_stew']) {
    assert.ok(chinese[`minecraft:${id}`].sources.some(source => source.type === 'HARVEST'), id);
  }
  for (const id of ['milk_bucket', 'potion', 'splash_potion', 'lingering_potion', 'iron_ingot']) {
    assert.equal(chinese[`minecraft:${id}`].sources, undefined, id);
  }
  assert.ok(chinese['minecraft:shield'].sources.some(source => source.type === 'TRADING'));
  assert.ok(!chinese['minecraft:iron_hoe'].sources.some(source => source.type === 'TRADING'));
});

test('regeneration is deterministic and retains independent manual text and numeric edits', () => {
  const sandbox = mkdtempSync(join(tmpdir(), 'originlore-presets-'));
  const paths = ['tools/presets', 'src/test/resources/reference/item_ids_1_21_1.txt',
    'build/preset-tools/lang_zh_cn.json', 'build/preset-tools/lang_en_us.json'];
  try {
    for (const path of paths) {
      assert.ok(existsSync(join(root, path)), `missing generator input: ${path}`);
      mkdirSync(join(sandbox, path, '..'), { recursive: true });
      cpSync(join(root, path), join(sandbox, path), { recursive: true });
    }
    const generate = () => execFileSync(process.execPath, ['tools/presets/_generate_all.mjs'], { cwd: sandbox, stdio: 'pipe' });
    const before = Object.fromEntries(['zh_cn', 'en_us'].map(language => [language, shards(sandbox, language)]));
    generate();
    for (const language of ['zh_cn', 'en_us']) assert.deepEqual(shards(sandbox, language), before[language]);
    const edit = (language, file, action) => {
      const path = join(sandbox, 'tools/presets', language, file);
      const entries = read(path);
      action(entries);
      writeFileSync(path, JSON.stringify(entries));
    };
    edit('zh_cn', 'colored_blocks.json', entries => {
      entries['minecraft:red_wool'].base.loreJson = [{ text: 'Manual wool copy', color: 'gold' }];
    });
    edit('zh_cn', 'weapons.json', entries => {
      const sword = entries['minecraft:iron_sword'];
      sword.base.loreJson = [{ text: 'Manual sword base' }];
      const crafted = sword.sources.find(source => source.type === 'CRAFTING').variants[0];
      crafted.rule.itemName = 'An authored quality name';
      crafted.rule.loreJson = [{ text: 'An authored quality description', bold: true }];
      crafted.weight = 123;
      crafted.rule.maxDamageRange = [175, 205];
      const chest = structuredClone(sword.sources.find(source => source.type === 'CHEST_LOOT'));
      chest.lootTableId = 'minecraft:chests/simple_dungeon';
      chest.variants[0].rule.loreJson = [{ text: 'An authored dungeon description' }];
      sword.sources.splice(1, 0, chest);
    });
    edit('en_us', 'building_blocks_gen.json', entries => {
      entries['minecraft:acacia_planks'].base.loreJson = [{ text: 'Independent English timber copy', italic: false }];
    });
    edit('en_us', 'food.json', entries => {
      const bread = entries['minecraft:bread'];
      bread.sources[0].variants[0].rule.itemName = 'An independently authored English name';
      bread.sources[0].variants[0].rule.loreJson = [{ text: 'Independent English bread copy', color: 'gold' }];
    });
    const edited = Object.fromEntries(['zh_cn', 'en_us'].map(language => [language, shards(sandbox, language)]));
    generate();
    generate();
    for (const language of ['zh_cn', 'en_us']) {
      const result = shards(sandbox, language);
      assert.deepEqual(result['minecraft:iron_sword'].sources, edited[language]['minecraft:iron_sword'].sources);
      assert.deepEqual(result, edited[language]);
    }
    assert.ok(existsSync(join(sandbox, 'tools/presets/_legacy_authored.json')));
  } finally {
    rmSync(sandbox, { recursive: true, force: true });
  }
});
