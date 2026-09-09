// One-off bootstrap for tools/presets/{exclusions,categories}.json.
// Deterministic: reads the committed reference id list and applies ordered rules, then reports anything unmatched,
// so an item can never be dropped silently.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const REFERENCE = 'src/test/resources/reference/item_ids_1_21_1.txt';
const ids = readFileSync(REFERENCE, 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#'))
  .map(line => line.replace(/^minecraft:/, ''));

const WOOD = '(?:acacia|bamboo|birch|cherry|crimson|dark_oak|jungle|mangrove|oak|spruce|warped)';
const COLOR = '(?:black|blue|brown|cyan|gray|green|light_blue|light_gray|lime|magenta|orange|pink|purple|red|white|yellow)';
const COPPER = '(?:waxed_)?(?:exposed_|weathered_|oxidized_)?';

const TECHNICAL = '技术性方块或开发者工具，生存模式无法获得';
const UNOBTAINABLE = '生存模式无法作为物品获得';

const EXCLUDED = new Map(Object.entries({
  air: '空气是空槽位的占位物品，无法持有',
  barrier: TECHNICAL, light: TECHNICAL, structure_void: TECHNICAL, structure_block: TECHNICAL, jigsaw: TECHNICAL,
  debug_stick: TECHNICAL, knowledge_book: '配方书内部物品，生存模式不会掉落',
  command_block: TECHNICAL, chain_command_block: TECHNICAL, repeating_command_block: TECHNICAL,
  command_block_minecart: TECHNICAL,
  spawner: '刷怪笼无法采集', trial_spawner: '试炼刷怪笼无法采集', vault: '宝库方块无法采集',
  reinforced_deepslate: UNOBTAINABLE, end_portal_frame: UNOBTAINABLE, bedrock: '基岩在生存模式无法破坏',
  budding_amethyst: '紫水晶母岩无法采集，也不能被活塞推动',
  farmland: '耕地被破坏时掉落泥土，物品本身生存无法获得',
  dirt_path: '土径被破坏时掉落泥土，物品本身生存无法获得',
  suspicious_sand: '可疑的沙子只能刷扫，无法作为物品获得',
  suspicious_gravel: '可疑的沙砾只能刷扫，无法作为物品获得',
  chorus_plant: '紫颂植物不掉落自身', frogspawn: '青蛙卵无法采集',
  petrified_oak_slab: UNOBTAINABLE,
  player_head: '原版生存模式没有玩家头颅的掉落或获取途径',
  bundle: '1.21.1 默认生存未启用收纳袋实验性功能，不能通过常规玩法获得',
  infested_stone: UNOBTAINABLE, infested_cobblestone: UNOBTAINABLE, infested_stone_bricks: UNOBTAINABLE,
  infested_mossy_stone_bricks: UNOBTAINABLE, infested_cracked_stone_bricks: UNOBTAINABLE,
  infested_chiseled_stone_bricks: UNOBTAINABLE, infested_deepslate: UNOBTAINABLE,
}));

// Ordered: the first matching rule wins, so specific families precede the broad block-shape catch-alls.
const RULES = [
  ['colored_blocks', new RegExp(`^${COLOR}_(?:banner|bed|candle|carpet|concrete|concrete_powder|dye|glazed_terracotta|shulker_box|stained_glass|stained_glass_pane|terracotta|wool)$`)],
  ['colored_blocks', /^(?:candle|shulker_box|terracotta)$/],

  ['redstone', new RegExp(`^(?:${WOOD}|stone|polished_blackstone)_(?:button|pressure_plate)$`)],
  ['redstone', /^(?:redstone|redstone_block|redstone_torch|repeater|comparator|piston|sticky_piston|observer|dispenser|dropper|hopper|hopper_minecart|tnt|tnt_minecart|tripwire_hook|lever|daylight_detector|note_block|redstone_lamp|target|crafter|trapped_chest|calibrated_sculk_sensor|sculk_sensor|slime_block|honey_block|rail|powered_rail|detector_rail|activator_rail|minecart|chest_minecart|furnace_minecart|lightning_rod|light_weighted_pressure_plate|heavy_weighted_pressure_plate)$/],
  ['redstone', new RegExp(`^${COPPER}copper_bulb$`)],

  ['tools', new RegExp(`^(?:wooden|stone|iron|golden|diamond|netherite)_(?:axe|hoe|pickaxe|shovel)$`)],
  ['tools', /^(?:shears|brush|fishing_rod|flint_and_steel|carrot_on_a_stick|warped_fungus_on_a_stick|spyglass|clock|compass|recovery_compass|map|filled_map|lead|name_tag|bucket|water_bucket|lava_bucket|milk_bucket|powder_snow_bucket|axolotl_bucket|cod_bucket|pufferfish_bucket|salmon_bucket|tropical_fish_bucket|tadpole_bucket|goat_horn|saddle)$/],

  ['weapons', new RegExp(`^(?:wooden|stone|iron|golden|diamond|netherite)_sword$`)],
  ['weapons', /^(?:bow|crossbow|trident|mace|shield|arrow|spectral_arrow|firework_rocket|firework_star|wind_charge|fire_charge)$/],

  ['armor', new RegExp(`^(?:leather|chainmail|iron|golden|diamond|netherite)_(?:helmet|chestplate|leggings|boots)$`)],
  ['armor', /^(?:turtle_helmet|elytra|wolf_armor|leather_horse_armor|iron_horse_armor|golden_horse_armor|diamond_horse_armor)$/],

  ['brewing', /^(?:brewing_stand|glass_bottle|potion|splash_potion|lingering_potion|tipped_arrow|dragon_breath|nether_wart|ominous_bottle|experience_bottle|blaze_powder|magma_cream|fermented_spider_eye|gunpowder|glowstone_dust|glistering_melon_slice|rabbit_foot|phantom_membrane|pufferfish|ghast_tear)$/],

  ['food', /^(?:apple|golden_apple|enchanted_golden_apple|golden_carrot|bread|cake|cookie|pumpkin_pie|mushroom_stew|rabbit_stew|beetroot_soup|suspicious_stew|dried_kelp|melon_slice|glow_berries|sweet_berries|chorus_fruit|popped_chorus_fruit|carrot|potato|baked_potato|poisonous_potato|beetroot|wheat|sugar|honey_bottle|egg)$/],
  ['food', /^(?:beef|cooked_beef|chicken|cooked_chicken|cod|cooked_cod|salmon|cooked_salmon|mutton|cooked_mutton|porkchop|cooked_porkchop|rabbit|cooked_rabbit|tropical_fish|rotten_flesh|spider_eye)$/],

  ['materials', /^(?:bolt|coast|dune|eye|flow|host|raiser|rib|sentry|shaper|silence|snout|spire|tide|vex|ward|wayfinder|wild)_armor_trim_smithing_template$/],
  ['materials', /^(?:netherite_upgrade_smithing_template|stick|string|leather|feather|bone|bone_meal|charcoal|coal|flint|clay_ball|brick|nether_brick|paper|book|writable_book|written_book|enchanted_book|prismarine_shard|prismarine_crystals|quartz|amethyst_shard|turtle_scute|armadillo_scute|rabbit_hide|honeycomb|shulker_shell|echo_shard|nether_star|disc_fragment_5|heart_of_the_sea|nautilus_shell|blaze_rod|breeze_rod|ender_pearl|ender_eye|lapis_lazuli|snowball|cocoa_beans|ink_sac|glow_ink_sac|slime_ball|heavy_core|trial_key|ominous_trial_key)$/],
  ['materials', /^(?:iron_ingot|gold_ingot|copper_ingot|netherite_ingot|netherite_scrap|iron_nugget|gold_nugget|raw_iron|raw_gold|raw_copper|ancient_debris|diamond|emerald)$/],
  ['materials', /^(?:wheat_seeds|beetroot_seeds|melon_seeds|pumpkin_seeds|torchflower_seeds|pitcher_pod)$/],
  ['materials', /^(?:angler|archer|arms_up|blade|brewer|burn|danger|explorer|flow|friend|guster|heart|heartbreak|howl|miner|mourner|plenty|prize|scrape|sheaf|shelter|skull|snort)_pottery_sherd$/],
  ['materials', /^(?:creeper|flow|flower|globe|guster|mojang|piglin|skull)_banner_pattern$/],

  ['natural_blocks', new RegExp(`^${WOOD}_(?:leaves|sapling|propagule|roots|nylium|fungus)$`)],
  ['natural_blocks', /^(?:grass_block|dirt|coarse_dirt|rooted_dirt|podzol|mycelium|mud|muddy_mangrove_roots|clay|sand|red_sand|gravel|soul_sand|soul_soil|netherrack|end_stone|snow|snow_block|ice|packed_ice|blue_ice|moss_block|moss_carpet|cobweb|kelp|seagrass|sea_pickle|vine|twisting_vines|weeping_vines|glow_lichen|hanging_roots|spore_blossom|big_dripleaf|small_dripleaf|pink_petals|lily_pad|cactus|sugar_cane|bamboo|dead_bush|short_grass|tall_grass|fern|large_fern|brown_mushroom|red_mushroom|brown_mushroom_block|red_mushroom_block|mushroom_stem|nether_sprouts|crimson_roots|warped_roots|nether_wart_block|warped_wart_block)$/],
  ['natural_blocks', /^(?:allium|azure_bluet|blue_orchid|cornflower|dandelion|lilac|lily_of_the_valley|orange_tulip|oxeye_daisy|peony|pink_tulip|poppy|red_tulip|rose_bush|sunflower|white_tulip|wither_rose|torchflower|pitcher_plant|chorus_flower|azalea|flowering_azalea|azalea_leaves|flowering_azalea_leaves)$/],
  ['natural_blocks', /^(?:(?:dead_)?(?:brain|bubble|fire|horn|tube)_coral|(?:dead_)?(?:brain|bubble|fire|horn|tube)_coral_(?:block|fan))$/],
  ['natural_blocks', /^(?:bee_nest|beehive|turtle_egg|sniffer_egg|sculk|sculk_vein|sculk_catalyst|sculk_shrieker|amethyst_cluster|small_amethyst_bud|medium_amethyst_bud|large_amethyst_bud|calcite|dripstone_block|pointed_dripstone|basalt|polished_basalt|smooth_basalt|magma_block|obsidian|crying_obsidian|melon|pumpkin|carved_pumpkin)$/],

  ['functional_blocks', /^(?:crafting_table|furnace|blast_furnace|smoker|stonecutter|loom|cartography_table|fletching_table|smithing_table|grindstone|enchanting_table|anvil|chipped_anvil|damaged_anvil|beacon|conduit|respawn_anchor|lodestone|lectern|bookshelf|chiseled_bookshelf|composter|barrel|chest|ender_chest|cauldron|bell|campfire|soul_campfire|jukebox|flower_pot|decorated_pot|scaffolding|ladder|armor_stand|item_frame|glow_item_frame|painting)$/],
  ['functional_blocks', /^(?:torch|soul_torch|lantern|soul_lantern|sea_lantern|glowstone|end_rod|chain|shroomlight|ochre_froglight|pearlescent_froglight|verdant_froglight|jack_o_lantern)$/],

  ['misc', new RegExp(`^${WOOD}_(?:boat|chest_boat)$`)],
  ['misc', /^bamboo_(?:raft|chest_raft)$/],
  ['misc', /^music_disc_/],
  ['misc', /^(?:creeper_head|dragon_head|piglin_head|skeleton_skull|wither_skeleton_skull|zombie_head|dragon_egg|end_crystal|totem_of_undying|bowl)$/],
];

// Stems take an optional plural and the usual block shapes, with the surface finishes factored into the prefix group.
const STEM = ['stone_brick', 'mossy_stone_brick', 'cobblestone', 'deepslate_brick', 'deepslate_tile',
  'cobbled_deepslate', 'polished_deepslate', 'deepslate', 'polished_blackstone_brick', 'polished_blackstone',
  'blackstone', 'polished_granite', 'polished_andesite', 'polished_diorite', 'granite', 'andesite', 'diorite',
  'red_sandstone', 'sandstone', 'mud_brick', 'packed_mud', 'end_stone_brick', 'red_nether_brick', 'nether_brick',
  'quartz_pillar', 'quartz_block', 'quartz_brick', 'quartz', 'purpur_pillar', 'purpur_block', 'purpur',
  'dark_prismarine', 'prismarine_brick',
  'prismarine', 'polished_tuff', 'tuff_brick', 'tuff', 'smooth_stone', 'stone', 'brick', 'bamboo_mosaic',
  'bamboo_block', 'glass_pane', 'glass', 'tinted_glass', 'bone_block', 'dried_kelp_block', 'gilded_blackstone',
  'wet_sponge', 'sponge', 'raw_iron_block', 'raw_gold_block', 'raw_copper_block', 'coal_block',
  'iron_block', 'gold_block', 'diamond_block', 'emerald_block', 'lapis_block', 'amethyst_block', 'netherite_block',
  'honeycomb_block', 'hay_block'];
const BUILDING_FAMILY = new RegExp(
  `^(?:cut_|smooth_|chiseled_|cracked_|mossy_|polished_|stripped_)?(?:${STEM.join('|')})s?(?:_slab|_stairs|_wall)?$`);
const BUILDING_SHAPE = new RegExp(
  `^(?:stripped_)?(?:${WOOD})_(?:planks|slab|stairs|fence|fence_gate|door|trapdoor|sign|hanging_sign|log|wood|stem|hyphae)$`);
const COPPER_BLOCK = new RegExp(
  `^${COPPER}(?:copper_block|chiseled_copper|cut_copper|copper_grate|copper_door|copper_trapdoor|copper)(?:_slab|_stairs)?$`);
const ORE = /^(?:deepslate_|nether_)?(?:coal|copper|iron|gold|redstone|lapis|diamond|emerald|quartz)_ore$/;
const IRONWORKS = /^(?:iron_bars|iron_door|iron_trapdoor|nether_brick_fence)$/;

const ORDER = ['building_blocks', 'natural_blocks', 'functional_blocks', 'redstone', 'colored_blocks', 'tools',
  'weapons', 'armor', 'food', 'brewing', 'materials', 'misc'];

function categorize(bare) {
  for (const [category, pattern] of RULES) {
    if (pattern.test(bare)) return category;
  }
  if (BUILDING_FAMILY.test(bare) || BUILDING_SHAPE.test(bare) || COPPER_BLOCK.test(bare) || ORE.test(bare)
      || IRONWORKS.test(bare)) {
    return 'building_blocks';
  }
  return null;
}

const categories = new Map(ORDER.map(key => [key, []]));
const exclusions = new Map();
const leftovers = [];

for (const bare of ids) {
  const id = `minecraft:${bare}`;
  if (bare.endsWith('_spawn_egg')) {
    exclusions.set(id, '刷怪蛋仅创造模式可得');
  } else if (EXCLUDED.has(bare)) {
    exclusions.set(id, EXCLUDED.get(bare));
  } else {
    const category = categorize(bare);
    if (category === null) leftovers.push(bare);
    else categories.get(category).push(id);
  }
}

mkdirSync('tools/presets', { recursive: true });
const categoryOut = {};
for (const key of ORDER) categoryOut[key] = categories.get(key).slice().sort();
writeFileSync('tools/presets/categories.json', JSON.stringify(categoryOut, null, 2) + '\n');
writeFileSync('tools/presets/exclusions.json',
  JSON.stringify(Object.fromEntries([...exclusions.entries()].sort()), null, 2) + '\n');

let categorized = 0;
for (const key of ORDER) {
  console.log(`${key.padEnd(18)} ${categories.get(key).length}`);
  categorized += categories.get(key).length;
}
console.log(`\ncategorized ${categorized} + excluded ${exclusions.size} = ${categorized + exclusions.size} of ${ids.length}`);
if (leftovers.length) {
  console.log(`LEFTOVERS (${leftovers.length}):`);
  console.log(leftovers.slice(0, 120).join(', '));
}
