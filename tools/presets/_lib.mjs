// Shared helpers for the tools/presets generators.
// Item display names come from the vanilla language files so they always match the client; only the lore is authored here.
// The language dumps live under build/ (git-ignored) because they are Mojang assets and must not be committed.
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const PRESETS = join(ROOT, 'tools', 'presets');
const MANIFEST = join(PRESETS, '_generated.json');
const COPY_MANIFEST = join(PRESETS, '_generated_copy.json');
const LANG_DUMP = { zh_cn: join(ROOT, 'build', 'preset-tools', 'lang_zh_cn.json'), en_us: join(ROOT, 'build', 'preset-tools', 'lang_en_us.json') };

let lang = null;
let categories = null;
let authored = null;
let generated = new Set();
const generatedCopy = existsSync(COPY_MANIFEST) ? JSON.parse(readFileSync(COPY_MANIFEST, 'utf8')) : {};

export function init() {
  if (lang) return;
  for (const code of ['zh_cn', 'en_us']) {
    if (!existsSync(LANG_DUMP[code])) {
      throw new Error(`missing vanilla language dump ${LANG_DUMP[code]} — regenerate it from the loom asset cache first`);
    }
  }
  lang = {
    zh_cn: JSON.parse(readFileSync(LANG_DUMP.zh_cn, 'utf8')),
    en_us: JSON.parse(readFileSync(LANG_DUMP.en_us, 'utf8')),
  };
  categories = JSON.parse(readFileSync(join(PRESETS, 'categories.json'), 'utf8'));
  generated = new Set(existsSync(MANIFEST) ? JSON.parse(readFileSync(MANIFEST, 'utf8')).shards : []);

  authored = new Map();
  const zhIds = new Set(), enIds = new Set();
  for (const code of ['zh_cn', 'en_us']) {
    const seen = code === 'zh_cn' ? zhIds : enIds;
    for (const file of readdirSync(join(PRESETS, code))) {
      if (!file.endsWith('.json')) continue;
      if (generated.has(file.slice(0, -'.json'.length))) continue;
      for (const id of Object.keys(JSON.parse(readFileSync(join(PRESETS, code, file), 'utf8')))) {
        seen.add(id);
        if (!authored.has(id)) authored.set(id, `${code}/${file}`);
      }
    }
  }
  for (const id of zhIds) if (!enIds.has(id)) throw new Error(`${id} authored in zh_cn only`);
  for (const id of enIds) if (!zhIds.has(id)) throw new Error(`${id} authored in en_us only`);
}

export function displayName(code, bare) {
  const table = lang[code];
  const value = table[`block.minecraft.${bare}`] ?? table[`item.minecraft.${bare}`];
  if (value == null) throw new Error(`no vanilla display name for minecraft:${bare}`);
  return value;
}

export function categoryIds(category) {
  return categories[category].map(id => id.replace('minecraft:', ''));
}

// Ids already covered by a hand-written shard are left alone, so re-running a generator never clobbers approved copy.
export function isAuthored(bare) {
  return authored.has(`minecraft:${bare}`);
}

export function emit(shard, entries) {
  const out = {};
  for (const entry of entries) {
    const id = `minecraft:${entry.id}`;
    if (authored.has(id)) throw new Error(`${id} is already authored in ${authored.get(id)} but was emitted again`);
    authored.set(id, shard);
    out[id] = entry;
  }
  for (const code of ['zh_cn', 'en_us']) {
    const lines = ['{'];
    const previousPath = join(PRESETS, code, `${shard}.json`);
    const previous = existsSync(previousPath) ? JSON.parse(readFileSync(previousPath, 'utf8')) : {};
    const ids = Object.keys(out);
    ids.forEach((id, index) => {
      const entry = out[id];
      const copy = code === 'zh_cn' ? entry.zh : entry.en;
      const oldLore = previous[id]?.base?.loreJson;
      const candidate = [{ text: copy.lore, color: entry.color, italic: true }];
      const previousGenerated = generatedCopy[code]?.[id];
      const managed = !oldLore || JSON.stringify(oldLore) === JSON.stringify(previousGenerated);
      const rule = [`"loreJson": ${JSON.stringify(managed ? candidate : oldLore)}`];
      generatedCopy[code] ??= {};
      generatedCopy[code][id] = candidate;
      lines.push(`${JSON.stringify(id)}: {`);
      lines.push(`  "base": { ${rule.join(', ')} } }${index === ids.length - 1 ? '' : ','}`);
    });
    lines.push('}');
    writeFileSync(join(PRESETS, code, `${shard}.json`), lines.join('\n') + '\n');
  }
  generated.add(shard);
  writeFileSync(MANIFEST, JSON.stringify({ shards: [...generated].sort() }, null, 2) + '\n');
  writeFileSync(COPY_MANIFEST, JSON.stringify(generatedCopy, null, 2) + '\n');
  return Object.keys(out).length;
}

// A lore table keyed by item id, with the display name filled in from the vanilla language files.
export function table(category, color, rarity, lore, skipAuthored = true) {
  init();
  const entries = [];
  const missing = [];
  for (const bare of categoryIds(category)) {
    if (skipAuthored && isAuthored(bare)) continue;
    if (!(bare in lore)) { missing.push(bare); continue; }
    const [zh, en] = lore[bare];
    entries.push({
      id: bare, color, rarity,
      zh: { name: displayName('zh_cn', bare), lore: zh },
      en: { name: displayName('en_us', bare), lore: en },
    });
  }
  return { entries, missing };
}
