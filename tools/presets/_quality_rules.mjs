import { readFileSync } from 'node:fs';

const reference = JSON.parse(readFileSync(new URL('./_vanilla_reference.json', import.meta.url), 'utf8'));
const range = (min, max = min) => ({ min, max });
const round = value => Math.round(value * 1000) / 1000;
const text = (value, color = 'gray') => [{ text: value, color, italic: true }];
const parts = bare => bare.split('_');
const idOf = bare => `minecraft:${bare}`;
const add = (set, value) => { if (!set.includes(value)) set.push(value); };

// These paths are implemented by Java mechanics rather than result items in recipe/loot JSON.
const traded = new Set(`apple bread cookie cake pumpkin_pie golden_carrot suspicious_stew cooked_porkchop cooked_chicken cooked_cod cooked_salmon rabbit_stew bow crossbow fishing_rod shears shield leather_horse_armor iron_axe iron_sword iron_pickaxe iron_shovel diamond_axe diamond_sword diamond_pickaxe diamond_shovel diamond_hoe stone_axe stone_shovel stone_pickaxe stone_hoe`.split(' '));
for (const material of ['leather', 'chainmail', 'iron', 'diamond']) {
  for (const piece of ['helmet', 'chestplate', 'leggings', 'boots']) traded.add(`${material}_${piece}`);
}
const mobEquipment = new Set(`bow crossbow trident iron_sword iron_shovel iron_axe golden_sword golden_axe`.split(' '));
for (const material of ['leather', 'chainmail', 'iron', 'golden', 'diamond']) {
  for (const piece of ['helmet', 'chestplate', 'leggings', 'boots']) mobEquipment.add(`${material}_${piece}`);
}
const cookedDrops = new Set(['cooked_beef', 'cooked_chicken', 'cooked_mutton', 'cooked_porkchop', 'cooked_rabbit', 'cooked_cod', 'cooked_salmon']);
const foodExclude = new Set(['ominous_bottle']);
const rawMeat = new Set(['beef', 'chicken', 'mutton', 'porkchop', 'rabbit']);
const fish = new Set(['cod', 'salmon', 'tropical_fish', 'pufferfish']);
const unsafeFood = new Set(['rotten_flesh', 'spider_eye', 'poisonous_potato', 'pufferfish']);

export function statsOf(bare) {
  if (bare === 'cake') return { nutrition: 2, saturation: 0.4, eatSeconds: 1.6, canAlwaysEat: false };
  if (bare.endsWith('_horse_armor')) return { armor: { leather: 3, iron: 5, golden: 7, diamond: 11 }[parts(bare)[0]] };
  return reference.stats[idOf(bare)] ?? {};
}

export function isFood(bare) {
  return !foodExclude.has(bare) && statsOf(bare).nutrition != null;
}

export function sourcesOf(bare) {
  const sources = [...(reference.sources[idOf(bare)] ?? [])];
  if (traded.has(bare)) add(sources, 'TRADING');
  if (mobEquipment.has(bare) || cookedDrops.has(bare)) add(sources, 'ENTITY_DROP');
  if (bare === 'elytra') add(sources, 'ENTITY_DROP');
  if (bare === 'suspicious_stew') add(sources, 'CRAFTING');
  if (['sweet_berries', 'glow_berries', 'honey_bottle', 'mushroom_stew', 'suspicious_stew'].includes(bare)) add(sources, 'HARVEST');
  if (bare === 'turtle_helmet' || bare === 'wolf_armor') add(sources, 'CRAFTING');
  return sources.sort();
}

function labels(code, kind, name) {
  const zh = code === 'zh_cn';
  const prefixes = { handmade: ['手工打制的', 'Handmade '], damaged: ['磨损的', 'Worn '], standard: ['工整的', 'Well-made '], refined: ['精制的', 'Refined '] };
  return `${prefixes[kind][zh ? 0 : 1]}${name}`;
}

function gearDetail(bare, kind, code) {
  const zh = code === 'zh_cn';
  let details;
  if (bare.endsWith('_sword')) details = {
    handmade: ['剑刃尚未磨匀，柄上的缠线也有些松，挥起来能觉出一点偏重。', 'The edge is uneven and the grip loosely wrapped; each swing pulls a little to one side.'],
    damaged: ['刃口磕出了缺口，剑身仍直，只是余下的锋口经不起多少次碰撞。', 'Nicks break the edge. The blade is still straight, but its remaining steel will take few more blows.'],
    standard: ['刃线磨得笔直，重心落在护手前方，握紧便能稳稳发力。', 'The edge runs true and the balance sits just ahead of the guard, steady in a firm grip.'],
    refined: ['从剑脊到刃口过渡匀净，护手与剑柄贴合得看不见缝隙。', 'The spine tapers cleanly to the edge, and the guard meets the grip without a gap.'],
  };
  else if (/(helmet|chestplate|leggings|boots|armor)$/.test(bare)) details = {
    handmade: ['接缝收得仓促，几个活动的位置略显僵硬，勉强能护住要害。', 'Hasty seams bind at the joints; it covers the vital parts, though movement is awkward.'],
    damaged: ['承受过重击的地方已经凹下去，接缝还撑着，不能再太依赖它。', 'Heavy blows have left dents. The seams still hold, though little strength remains in them.'],
    standard: ['边缘磨平，接缝牢靠，活动时没有多余的摩擦声。', 'Smooth edges and sound seams move together without an unwanted scrape.'],
    refined: ['受力处加固得恰到好处，内侧也仔细打磨过，贴身而不硌人。', 'Reinforcement follows the lines of strain, with the inside finished smooth against the wearer.'],
  };
  else if (bare === 'bow' || bare === 'crossbow') details = {
    handmade: ['弓臂弯得不大均匀，弦结倒还牢固，拉满时要留一点余地。', 'The limbs bend unevenly. The string knots hold, though a full draw leaves little margin.'],
    damaged: ['弓臂已经受潮，弦上起了毛，蓄起的力气有一半留在了吱响里。', 'Damp limbs and a fraying string waste their tension in a long creak.'],
    standard: ['两端受力均匀，弦松开的瞬间干脆利落，没有拖泥带水的颤声。', 'Both limbs take the load evenly and release with a clean snap.'],
    refined: ['弓臂回弹有力，弦道也修得笔直，发射时几乎没有多余的震动。', 'Springy limbs and a straight string path send the shot away with little wasted vibration.'],
  };
  else if (bare === 'elytra') details = {
    handmade: ['翼膜的补缀处略显厚重，展开时要多费一点力气。', 'Heavy patches stiffen the membranes, requiring care as the wings unfold.'],
    damaged: ['翼膜边缘磨得透薄，有几道旧裂口靠细线勉强连着。', 'The membranes are worn thin at the edges, with old tears held by fine thread.'],
    standard: ['翼膜舒展平整，几根支脉都完好，风能顺着翼面滑过去。', 'The membranes spread evenly over intact ribs, letting air pass cleanly across them.'],
    refined: ['两翼轻而对称，翼缘没有一点卷折，迎风展开时十分安稳。', 'Light, symmetrical wings have uncreased edges and settle steadily into the air.'],
  };
  else if (bare === 'trident' || bare === 'mace' || bare === 'shield') details = {
    handmade: ['握持处还留着粗糙的加工痕迹，接合处敲起来略有空响。', 'Rough finishing marks remain at the grip, and the joint sounds a little hollow when tapped.'],
    damaged: ['迎着冲击的一面伤痕交错，柄与主体之间已经有些松动。', 'Scars cross the striking face, and the grip has begun to loosen at its joint.'],
    standard: ['接合处紧密牢靠，握在手里分量扎实，发力时不会乱晃。', 'A tight joint and a solid grip keep its weight steady under strain.'],
    refined: ['受力的地方留足了余量，握柄也修得合手，细看才见得出工夫。', 'Careful reinforcement and a fitted grip reveal the work only on close inspection.'],
  };
  else details = {
    handmade: ['柄与工作端接得略偏，边缘也没有细磨，干活时得多费些力气。', 'The working end sits slightly off the handle and lacks a fine finish, making each task harder.'],
    damaged: ['常受力的边缘已经磨圆，柄上几道裂纹让人不敢使尽力气。', 'The working edge has rounded off, and cracks in the handle discourage a hard grip.'],
    standard: ['工作端打磨得当，握柄扎实，做起常用的活计相当顺手。', 'A properly finished working end and a sound handle suit steady everyday work.'],
    refined: ['工作端的角度修得很准，握柄也配得合宜，力气几乎不会白费。', 'An accurately shaped working end and a fitted handle put nearly every effort to use.'],
  };
  return details[kind][zh ? 0 : 1];
}

function armorSlot(bare) {
  if (bare.endsWith('helmet')) return 'head';
  if (bare.endsWith('chestplate')) return 'chest';
  if (bare.endsWith('leggings')) return 'legs';
  if (bare.endsWith('boots')) return 'feet';
  return 'body';
}

function gearRule(bare, kind, code, name, baseLore) {
  const stats = statsOf(bare);
  const power = { handmade: range(0.65, 0.85), damaged: range(0.55, 0.7), standard: range(1), refined: range(1.05, 1.2) }[kind];
  const durability = { handmade: [0.6, 0.8], damaged: [0.3, 0.45], standard: [1, 1], refined: [1.1, 1.2] }[kind];
  const rule = { itemName: labels(code, kind, name), loreJson: text(`${baseLore} ${gearDetail(bare, kind, code)}`, kind === 'refined' ? 'blue' : kind === 'standard' ? 'white' : 'gray') };
  if (stats.durability) rule.maxDamageRange = durability.map(factor => Math.max(1, Math.round(stats.durability * factor)));
  const isArmor = bare !== 'wolf_armor' && /(helmet|chestplate|leggings|boots|armor)$/.test(bare);
  if (isArmor) {
    const amount = { handmade: range(-1, -0.5), damaged: range(-1.5, -1), standard: range(0), refined: range(0.5, 1) }[kind];
    const slot = armorSlot(bare);
    rule.attributes = [{ attribute: 'minecraft:generic.armor', id: `originlore:preset_quality_armor.${slot}`, amountRange: amount, operation: 'add_value', slot }];
    rule.appendAttributes = true;
  }
  if (stats.attack != null) {
    const low = Math.min(2, Math.max(0, stats.attack - 1));
    rule.attackDamageRange = kind === 'handmade' ? range(-low, -low / 2)
      : kind === 'damaged' ? range(-low / 2, 0) : kind === 'refined' ? range(0.5, 1) : range(0);
  }
  if (['bow', 'crossbow', 'trident'].includes(bare)) rule.projectileDamageMultiplier = power;
  if (/_(axe|hoe|pickaxe|shovel)$/.test(bare) || bare === 'shears') rule.tool = { miningSpeedMultiplier: power };
  if (bare === 'iron_sword') {
    const names = { handmade: ['手工打制铁剑', 'Hand-forged Iron Sword'], damaged: ['剑刃有缺口的铁剑', 'Notched Iron Sword'], standard: ['铁匠打造的铁剑', "Smith-forged Iron Sword"], refined: ['精钢铁剑', 'Fine Steel Sword'] };
    rule.itemName = names[kind][code === 'zh_cn' ? 0 : 1];
    rule.attackDamageRange = { handmade: range(-2, -1), damaged: range(0), standard: range(0), refined: range(1) }[kind];
    rule.maxDamageRange = { handmade: [150, 200], damaged: [75, 75], standard: [250, 250], refined: [275, 275] }[kind];
    if (kind === 'handmade') rule.loreJson = text(code === 'zh_cn'
      ? '剑身上还留着深浅不一的锤痕，刃口也磨得不甚齐整。虽然不太趁手，但好在制作较为简单。'
      : 'Hammer marks of uneven depth remain on the blade, and the edge is far from neatly ground. It is awkward in the hand, but at least it is simple to make.');
  }
  return rule;
}

const gearWeights = {
  CHEST_LOOT: { damaged: 70, standard: 25, refined: 5 }, ENTITY_DROP: { damaged: 80, standard: 18, refined: 2 },
  FISHING: { damaged: 80, standard: 18, refined: 2 }, ARCHAEOLOGY: { damaged: 85, standard: 13, refined: 2 },
  VAULT: { damaged: 60, standard: 33, refined: 7 }, TRADING: { damaged: 60, standard: 35, refined: 5 },
  GIFT: { damaged: 65, standard: 30, refined: 5 }, BARTER: { damaged: 75, standard: 22, refined: 3 },
  CRAFTING: { handmade: 85, standard: 13, refined: 2 }, SMITHING: { handmade: 65, damaged: 10, standard: 22, refined: 3 },
};

function foodFamily(bare) {
  if (unsafeFood.has(bare)) return 'unsafe';
  if (rawMeat.has(bare)) return 'raw_meat';
  if (fish.has(bare)) return 'fish';
  if (bare.startsWith('cooked_')) return 'cooked';
  if (bare.endsWith('_stew') || bare.endsWith('_soup')) return 'stew';
  if (['bread', 'cookie', 'cake', 'pumpkin_pie', 'dried_kelp'].includes(bare)) return 'ration';
  if (bare.includes('golden_')) return 'golden';
  if (bare === 'honey_bottle') return 'honey';
  return 'produce';
}

const foodDetails = {
  raw_meat: [
    ['切口暗沉，肉汁带着黏意，即使肚子空着也得先闻一闻。', 'The cut is dark and its juices tacky; even an empty stomach calls for a careful sniff.'],
    ['肉色尚好，筋膜也没有变干，离开火之前终究还是生的。', 'The flesh has kept its colour and the membrane is moist, though it remains raw until cooked.'],
    ['切面紧实，肉汁清亮，适合尽快架在火上料理。', 'Firm flesh and clear juices make this a cut worth taking straight to the fire.'],
  ],
  fish: [
    ['鱼鳃发暗，腹侧已经松软，鲜味被一股腥气盖了过去。', 'Dark gills and a soft belly let a stale smell overpower what freshness remains.'],
    ['鱼鳞还带着湿光，肉贴着骨，收拾干净便是一顿简单的饭。', 'Wet scales catch the light and the flesh holds to the bones, enough for a simple meal after cleaning.'],
    ['鳃色清亮，鱼肉紧致，刚离开水时的光泽还没有退。', 'Bright gills and firm flesh still carry the sheen of a fish newly taken from water.'],
  ],
  cooked: [
    ['外层烤得发硬，油脂结在表面，嚼一口得慢慢等它松开。', 'A hard outer layer and set fat make each mouthful take its time.'],
    ['热气虽已散去，肉纤维还算松软，焦香里没有明显的苦味。', 'The heat has faded, but the flesh is tender and the browned surface has little bitterness.'],
    ['表层薄脆，里面仍含着肉汁，火候停在了最合适的时候。', 'A thin browned crust holds the juices inside, taken from the heat at just the right moment.'],
  ],
  stew: [
    ['汤面蒙着一层凝住的油，碗底的碎料也已发软，闻着不大放心。', 'Set fat films the surface and the pieces at the bottom have softened past their best.'],
    ['汤水与食材还没有分开，搅匀之后能闻出原料各自的味道。', 'The broth still holds its ingredients together, releasing their separate scents when stirred.'],
    ['汤底清润，食材煮得软而不散，一勺便能尝出耐心。', 'A clean broth carries ingredients cooked tender without falling apart.'],
  ],
  ration: [
    ['边缘干硬，轻轻一碰便落下碎屑，勉强还能当作路上的干粮。', 'Dry edges shed crumbs at a touch, still serviceable as provisions for the road.'],
    ['外层收得干爽，掰开还看得见细密的纹理，带在路上不占地方。', 'A dry surface encloses a fine texture, compact enough to travel well.'],
    ['外层酥松，里面的组织细而均匀，余香比寻常干粮留得更久。', 'A crisp outside and an even crumb leave a longer flavour than ordinary trail provisions.'],
  ],
  produce: [
    ['表皮皱起，磕伤的地方已经发软，水分和清香都少了许多。', 'Wrinkled skin and soft bruises have lost much of their moisture and fresh scent.'],
    ['表皮完整，里面还有水分，洗去尘土便能尝到朴素的甜味。', 'Intact skin holds the moisture in; washed clean, it keeps a plain, quiet sweetness.'],
    ['表皮饱满，切开汁水充足，清香里没有久放的杂味。', 'Full skin and plenty of juice carry a clean scent without the taint of storage.'],
  ],
  golden: [
    ['金箔贴得松散，露出的果肉已有干痕，光泽还在，口感却差了。', 'Loose gold leaf exposes dried flesh; the shine remains, though the texture has suffered.'],
    ['金层包覆完整，里面的果肉没有失水，握在手里沉甸甸的。', 'An intact gold covering keeps the flesh moist and gives it a solid weight.'],
    ['金层压得细密，果肉与光泽都保存得近乎完好，没有一点压伤。', 'Close-fitted gold protects flesh and shine alike, with scarcely a bruise beneath it.'],
  ],
  honey: [
    ['瓶底积着浑浊的沉淀，蜜香里夹了点酸气，倒出来也不再匀净。', 'Cloudy sediment and a sour note disturb the scent, and it no longer pours evenly.'],
    ['蜜液缓缓挂在瓶壁上，少许结晶沉在底部，甜味仍然纯正。', 'Honey slowly coats the glass above a few crystals, keeping its clean sweetness.'],
    ['清亮的蜜液拉出细丝，花香封在瓶里，开口才缓缓散出来。', 'Clear honey draws into fine threads, releasing a sealed floral scent as the bottle opens.'],
  ],
  unsafe: [
    ['颜色已经深得不自然，气味也更浓烈，吃下去恐怕要付出些代价。', 'Its colour has deepened unnaturally and its smell grown stronger; swallowing it promises a cost.'],
    ['还能辨出原来的形状，但那股刺鼻的味道说明它本就不是好食材。', 'Its original shape remains, though the sharp smell says it was never a good ingredient.'],
    ['保存得相对完整，危险的成分却不会因为看着干净就消失。', 'It has been kept relatively intact, but a clean appearance does not remove what makes it dangerous.'],
  ],
};

function foodRule(bare, level, code, name, baseLore) {
  const stats = statsOf(bare);
  const family = foodFamily(bare);
  const zh = code === 'zh_cn';
  const prefixes = zh ? ['放陈的', '寻常的', '上好的'] : ['Stale ', 'Plain ', 'Choice '];
  const nutritionFactors = [[0.45, 0.65], [0.85, 1], [1, 1.1]][level];
  const saturationFactors = [[0.35, 0.6], [0.8, 1], [1.05, 1.2]][level];
  const secondsFactors = [[1.35, 1.7], [1, 1.1], [0.8, 1]][level];
  const effects = [];
  const food = {
    nutritionRange: nutritionFactors.map(factor => Math.max(1, Math.round(stats.nutrition * factor))),
    saturationRange: range(...saturationFactors.map(factor => round(stats.saturation * factor))),
    eatSecondsRange: range(...secondsFactors.map(factor => round(stats.eatSeconds * factor))),
    canAlwaysEat: stats.canAlwaysEat,
    appendEffects: true,
    effects,
  };
  if (level === 0) {
    effects.push({ id: 'minecraft:hunger', duration: 400, amplifier: 0, probability: family === 'unsafe' ? 0.65 : 0.35 });
    effects.push({ id: 'minecraft:nausea', duration: 160, amplifier: 0, probability: family === 'raw_meat' || family === 'fish' ? 0.3 : 0.15 });
  }
  let lore = `${baseLore} ${foodDetails[family][level][zh ? 0 : 1]}`;
  if (bare === 'bread' && level === 0) lore = zh
    ? '外皮已经干裂，掰开便落下一把碎屑——作为填饱肚子而言，还算能吃的干粮。'
    : 'The crust has cracked dry, and breaking it scatters a handful of crumbs. As something to fill an empty stomach, it is still edible trail bread.';
  return { itemName: `${prefixes[level]}${name}`, loreJson: text(lore, ['gray', 'white', 'green'][level]), food };
}

export function qualitySources(bare, code, name, baseLore) {
  const stats = statsOf(bare);
  const food = isFood(bare);
  if (!food && stats.durability == null && stats.armor == null) return [];
  const sources = sourcesOf(bare);
  if (sources.length === 0) throw new Error(`${bare}: quality item has no real acquisition source`);
  return sources.map(type => {
    if (!food) {
      const weights = bare === 'iron_sword' && type === 'CRAFTING' ? { handmade: 100 } : gearWeights[type];
      if (!weights) throw new Error(`${bare}: gear source ${type} has no quality balance`);
      return { type, variants: Object.entries(weights).map(([kind, weight]) => ({ id: kind, weight,
        qualityScore: { handmade: 0.3, damaged: 0.15, standard: 0.5, refined: 0.9 }[kind],
        rule: gearRule(bare, kind, code, name, baseLore) })) };
    }
    const weights = type === 'CHEST_LOOT' || type === 'ARCHAEOLOGY' ? [75, 23, 2]
      : type === 'SMELTING' ? [60, 35, 5] : type === 'TRADING' ? [60, 35, 5]
      : type === 'VAULT' ? [65, 30, 5] : type === 'ENTITY_DROP' ? [75, 23, 2] : [65, 32, 3];
    const source = { type, variants: ['stale', 'ordinary', 'choice'].map((id, level) => ({
      id, weight: weights[level], qualityScore: [0.15, 0.5, 0.9][level], spoilage: [0.8, 0.25, 0.03][level],
      ingredientWeights: [0, 0.5, 1].map((quality, i) => ({ quality, multiplier: [[1.8, 1, 0.35], [0.7, 1, 1.2], [0.2, 1, 3]][level][i] })),
      rule: foodRule(bare, level, code, name, baseLore),
    })) };
    if (type === 'CRAFTING' || type === 'SMELTING') source.processing = {
      riskRetention: type === 'SMELTING' ? 0.35 : 0.65,
      riskFloor: type === 'SMELTING' ? 0.05 : 0.1,
      effects: [{ id: 'minecraft:hunger', duration: 300, amplifier: 0, probability: 0.6 },
        { id: 'minecraft:nausea', duration: 120, amplifier: 0, probability: 0.25 }],
    };
    return source;
  });
}
