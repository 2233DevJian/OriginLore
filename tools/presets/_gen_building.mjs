// Generates the building_blocks shards. Display names come from the vanilla language files; lore is authored once
// per family (a base material, a block shape, a copper patina stage or an ore host) and reused across the family.
import { init, displayName, categoryIds, isAuthored, emit } from './_lib.mjs';

init();

const WOODS = ['acacia', 'bamboo', 'birch', 'cherry', 'crimson', 'dark_oak', 'jungle', 'mangrove', 'oak', 'spruce', 'warped'];
const WOOD_DETAIL = {
  acacia: ['金合欢的橙红木色留得鲜明。', 'The orange-red acacia colour remains distinct.'],
  bamboo: ['竹节的浅黄纹理细而笔直。', 'Pale yellow bamboo grain runs fine and straight.'],
  birch: ['白桦木色浅淡，纹路也很细。', 'Birch is pale in colour and fine in grain.'],
  cherry: ['樱花木透着浅粉，切面颜色柔和。', 'Cherry wood shows a soft pink colour at the cut.'],
  crimson: ['绯红菌材带着深红纤维，不会烧起来。', 'Crimson fungal timber has deep red fibres and does not burn.'],
  dark_oak: ['深色橡木颜色厚重，木纹深浅分明。', 'Dark oak has a deep colour and clearly shaded grain.'],
  jungle: ['丛林木偏红褐，宽纹理看得很清楚。', 'Jungle timber is reddish brown with visible broad grain.'],
  mangrove: ['红树木色偏深红，和浅石材并排很清楚。', 'Mangrove has a deep red colour that contrasts with pale stone.'],
  oak: ['橡木的黄褐纹理平直，颜色很朴素。', 'Oak has straight yellow-brown grain and a plain colour.'],
  spruce: ['云杉木色深褐，密纹让边面显得整齐。', 'Spruce has a deep brown colour with close grain along the edge.'],
  warped: ['诡异菌材透着蓝绿，耐火的纤维很干。', 'Warped timber is blue-green, with dry fireproof fibres.'],
};

// [color, zh, en] for every solid block that has its own identity rather than being a shape of something else.
// stone, stone_bricks and bricks are hand-written elsewhere and are skipped on output; they stay here so the
// slabs, stairs and walls cut from them inherit the right text colour.
const BASE = {
  stone: ['white', '', ''],
  stone_bricks: ['white', '', ''],
  bricks: ['dark_red', '', ''],
  cobblestone: ['white', '圆滚滚的碎石头垒成的，缝隙里全是灰。', 'Broken stone piled into a block, with grit worked into every gap.'],
  mossy_cobblestone: ['green', '圆石的缝里长满了苔藓，摸上去一直是湿的。', 'Moss grown through every gap in the rubble, and damp to the touch.'],
  mossy_stone_bricks: ['green', '砖缝里钻进了苔藓，看得出在户外待了很久。', 'Moss has taken the mortar, which tells you this sat outside a long while.'],
  cracked_stone_bricks: ['white', '砖面裂了缝，还撑得住，只是不再平整。', 'Cracks running through the brick face — still sound, no longer flat.'],
  chiseled_stone_bricks: ['white', '正中錾了一个方纹，是砌墙的人留的记号。', 'A square device chiselled into the middle, left there by whoever laid it.'],
  smooth_stone: ['white', '磨平的石面，摸不出一粒砂。', 'Stone ground flat until not a grain is left under the hand.'],
  granite: ['white', '带粉红斑点的火成岩，比寻常石头硬。', 'Igneous rock flecked pink, and harder than plain stone.'],
  polished_granite: ['white', '磨过的花岗岩，斑点排得整齐，能反出一点光。', 'Granite polished until the flecks line up and throw a little light.'],
  diorite: ['white', '灰白杂色的岩块，纹理没什么规律。', 'A grey-and-white rock with no pattern worth naming.'],
  polished_diorite: ['white', '磨过的闪长岩，表面发亮，颜色偏冷。', 'Diorite polished to a shine, and cold in colour.'],
  andesite: ['white', '灰绿的安山岩，粗糙，但很结实。', 'Grey-green andesite, rough under the palm and very solid.'],
  polished_andesite: ['white', '磨过的安山岩，颜色匀净，像蒙了一层灰釉。', 'Andesite ground even, as though a grey glaze had been laid over it.'],

  deepslate: ['dark_gray', '深层的暗色岩，比石头重，也更难敲开。', 'Dark rock from far down — heavier than stone and slower to break.'],
  cobbled_deepslate: ['dark_gray', '敲碎的深层岩，断面发黑。', 'Deepslate broken into rubble, black at every fresh edge.'],
  polished_deepslate: ['dark_gray', '磨平的深层岩，黑得很均匀。', 'Deepslate ground flat, and uniformly black.'],
  deepslate_bricks: ['dark_gray', '深层岩切的砖，颜色比石砖沉得多。', 'Bricks cut from deepslate, far darker than any stone brick.'],
  cracked_deepslate_bricks: ['dark_gray', '裂了缝的深层岩砖，缝宽得能塞进指甲。', 'Deepslate brick split wide enough to catch a fingernail.'],
  deepslate_tiles: ['dark_gray', '细长的小瓦片排成一层层，铺地很平。', 'Narrow tiles laid course on course, flat enough to walk true.'],
  cracked_deepslate_tiles: ['dark_gray', '瓦片裂了几处，踩上去会有细响。', 'A few tiles have cracked; they tick underfoot.'],
  chiseled_deepslate: ['dark_gray', '錾出回纹的深层岩，一般用在门楣上。', 'Deepslate chiselled into a fret, usually set over a doorway.'],

  blackstone: ['dark_gray', '下界的黑色火山岩，敲开有玻璃一样的断面。', 'Black volcanic rock from the Nether, glossy where it splits.'],
  polished_blackstone: ['dark_gray', '磨亮的黑石，黑得能照出个人影。', 'Blackstone burnished until it almost shows a face.'],
  polished_blackstone_bricks: ['dark_gray', '黑石切的砖，砌一整面墙都是暗的。', 'Bricks cut from blackstone; a whole wall of them stays dark.'],
  cracked_polished_blackstone_bricks: ['dark_gray', '裂开的黑石砖，堡垒遗迹的墙上到处都是。', 'Split blackstone brick, common on the walls of a bastion.'],
  chiseled_polished_blackstone: ['dark_gray', '錾出骷髅纹的黑石，是猪灵留下的记号。', 'Blackstone chiselled with a skull — a piglin mark.'],
  gilded_blackstone: ['yellow', '黑石里嵌着金粒，一镐下去也可能什么都得不到。', 'Gold seeded through blackstone; one swing may still yield nothing.'],

  tuff: ['gray', '火山灰压成的凝灰岩，轻，一敲就掉渣。', 'Ash pressed into tuff — light, and it sheds grit when struck.'],
  polished_tuff: ['gray', '磨平的凝灰岩，还是那种灰绿色。', 'Tuff ground flat, still that grey-green.'],
  tuff_bricks: ['gray', '凝灰岩切的小砖，颜色很匀。', 'Small bricks cut from tuff, even in colour.'],
  chiseled_tuff: ['gray', '錾出纹样的凝灰岩，纹路浅得容易积灰。', 'Tuff chiselled with a pattern shallow enough to hold dust.'],
  chiseled_tuff_bricks: ['gray', '砖面上錾了花纹的凝灰岩。', 'Tuff brick with a device worked into the face.'],

  sandstone: ['white', '沙子压实成的岩层，一刀切下去还会掉砂。', 'Sand pressed into rock that still sheds grains under a blade.'],
  chiseled_sandstone: ['white', '錾出纹样的砂岩，沙漠神殿里常见。', 'Sandstone chiselled with a device, common in desert temples.'],
  cut_sandstone: ['white', '切齐了棱角的砂岩，比原块干净。', 'Sandstone cut to a clean edge, tidier than the raw block.'],
  smooth_sandstone: ['white', '磨平的砂岩，摸上去像陶。', 'Sandstone ground smooth until it feels like pottery.'],
  red_sandstone: ['white', '红沙压成的岩层，颜色比砂岩深。', 'Red sand pressed into rock, deeper in colour than its pale cousin.'],
  chiseled_red_sandstone: ['white', '錾出纹样的红砂岩，纹样和砂岩那边一样。', 'Red sandstone chiselled with the same devices the pale sort carries.'],
  cut_red_sandstone: ['white', '切齐了棱角的红砂岩。', 'Red sandstone cut to a clean edge.'],
  smooth_red_sandstone: ['white', '磨平的红砂岩，红得很匀。', 'Red sandstone ground smooth, and evenly red.'],

  nether_bricks: ['dark_red', '下界砖烧成的深色块，摸上去还带着一点温。', 'Nether brick fired into a dark block that still feels faintly warm.'],
  cracked_nether_bricks: ['dark_red', '裂了缝的下界砖，堡垒的墙上到处是。', 'Split nether brick, everywhere on a fortress wall.'],
  chiseled_nether_bricks: ['dark_red', '錾出纹样的下界砖，纹路里还积着灰。', 'Nether brick chiselled with a device, ash settled in the grooves.'],
  red_nether_bricks: ['dark_red', '掺了下界疣一起烧的砖，颜色偏红。', 'Brick fired with nether wart folded in, which reddens it.'],

  end_stone_bricks: ['white', '末地石切的砖，白得发冷。', 'Bricks cut from end stone, white enough to look cold.'],
  mud_bricks: ['gray', '泥晒成的砖，轻，但怕水泡。', 'Mud dried into brick — light, and ruined by standing water.'],
  packed_mud: ['gray', '压实的泥块，干透之后勉强能踩。', 'Mud packed hard; once properly dry it will bear a footstep.'],

  prismarine: ['dark_aqua', '海底神殿的石材，纹理会随光线变。', 'Stone from an ocean monument, its pattern shifting with the light.'],
  prismarine_bricks: ['dark_aqua', '切成砖的海晶石，比原块整齐得多。', 'Prismarine cut into brick, far tidier than the raw block.'],
  dark_prismarine: ['dark_aqua', '颜色最深的海晶石，中间嵌着深色的纹。', 'The darkest prismarine, with a heavier pattern running through it.'],

  purpur_block: ['dark_purple', '紫颂果烧成的紫色石材，末地城里到处都是。', 'Chorus fruit fired into purple stone, all over an end city.'],
  purpur: ['dark_purple', '紫颂果烧成的紫色石材，末地城里到处都是。', 'Chorus fruit fired into purple stone, all over an end city.'],
  purpur_pillar: ['dark_purple', '磨成圆柱的紫珀块，纹路是竖着走的。', 'Purpur turned into a pillar, its grain running upright.'],

  quartz_block: ['white', '下界石英压成的白块，干净得没什么瑕疵。', 'Nether quartz pressed into a white block, clean of any flaw.'],
  quartz: ['white', '下界石英压成的白块，干净得没什么瑕疵。', 'Nether quartz pressed into a white block, clean of any flaw.'],
  quartz_bricks: ['white', '石英切的小砖，白得很匀。', 'Small bricks cut from quartz, evenly white.'],
  quartz_pillar: ['white', '磨成圆柱的石英，纹路竖直。', 'Quartz turned into a pillar, its grain upright.'],
  chiseled_quartz_block: ['white', '錾出纹样的石英块，一般放在柱头。', 'Quartz chiselled with a device, usually set at the top of a column.'],
  smooth_quartz: ['white', '磨平的石英，白得像瓷。', 'Quartz ground flat, white as porcelain.'],

  glass: ['white', '烧透的沙子，能看出去，也一拳就能打碎。', 'Sand fired until it clears — see straight through it, break it with one blow.'],
  glass_pane: ['white', '薄玻璃片，装窗比整块省料。', 'Thin glass, cheaper than a full block for filling a window.'],
  tinted_glass: ['dark_gray', '掺了紫水晶的玻璃，透得过光，透不过视线。', 'Glass folded with amethyst: light gets through, your view does not.'],

  sponge: ['yellow', '海里捞上来的海绵，能吸掉一大片水。', 'Sponge pulled from the sea floor, good for drinking up a whole pool.'],
  wet_sponge: ['yellow', '吸饱了水的海绵，得先进炉子烤干。', 'Sponge heavy with water; it wants the furnace before it is any use.'],

  coal_block: ['dark_gray', '煤压成的一整块，烧起来比散煤久得多。', 'Coal pressed into one block, and it burns far longer loose coal would.'],
  iron_block: ['white', '九个铁锭压成的方块，也常拿来当砧用。', 'Nine ingots pressed into a block, often used as an anvil in a pinch.'],
  gold_block: ['yellow', '九个金锭压成的，好看，但实在没什么用。', 'Nine gold ingots pressed together — handsome, and of no real use.'],
  diamond_block: ['aqua', '九颗钻石压成的，摆着比用掉划算。', 'Nine diamonds pressed into one; keeping it beats spending it.'],
  emerald_block: ['green', '九颗绿宝石压成的，村民见了会更客气些。', 'Nine emeralds pressed into one, which makes villagers more polite.'],
  lapis_block: ['blue', '青金石压成的蓝块，颜色深得发沉。', 'Lapis pressed into a block, blue heavy enough to look sunk.'],
  amethyst_block: ['light_purple', '紫水晶簇的底座，敲上去会响一声。', 'The bed a cluster grows from; knock it and it rings once.'],
  netherite_block: ['dark_gray', '九个下界合金锭压成的，扔进火里也烧不化。', 'Nine netherite ingots pressed together, and it will not burn.'],
  raw_iron_block: ['white', '没炼过的原铁矿压成一整块，还得进炉子。', 'Unsmelted iron ore pressed into a block, still bound for the furnace.'],
  raw_gold_block: ['yellow', '没炼过的原金矿压成一整块，还得进炉子。', 'Unsmelted gold ore pressed into a block, still bound for the furnace.'],
  raw_copper_block: ['white', '没炼过的原铜矿压成一整块，还得进炉子。', 'Unsmelted copper ore pressed into a block, still bound for the furnace.'],

  hay_block: ['yellow', '压成方的干草，马吃了能缓过来，摔下来也能垫一下。', 'Hay pressed into a block: it settles a horse and cushions a long fall.'],
  honeycomb_block: ['yellow', '蜂房拼成的块，闻着有甜味，走上去却不粘脚。', 'Combs fitted into a block — it smells sweet but does not grip your boots.'],
  bone_block: ['white', '骨头压成的白块，像一段埋在土里的化石。', 'Bone pressed into a white block, like a length of fossil dug up.'],
  dried_kelp_block: ['green', '晒干的海带压成的块，当燃料比当食物合适。', 'Kelp dried and pressed; better as fuel than as food.'],

  bamboo_block: ['green', '一捆竹子压成的方料，比木板轻。', 'A bundle of bamboo pressed into a block, lighter than plank.'],
  bamboo_mosaic: ['green', '竹片拼出的细密花纹，凑近了才看得见接缝。', 'Bamboo slivers set into a tight pattern; you only find the joins up close.'],
};

// Slab/stairs/wall take their lore from the shape and their text colour from the material they were cut from.
const SHAPE_LORE = {
  _slab: ['切成半格的薄块，铺路和垫高都趁手。', 'Cut to half height — handy for a path or for levelling a floor.'],
  _stairs: ['带踏步的斜块，落差大的地方省得跳上跳下。', 'A stepped block that saves the jump wherever the floor changes height.'],
  _wall: ['垒起来的矮墙，能拦人，也能顺着它认路。', 'A low wall that stops a walker and gives you a line to follow.'],
};
const STEM_ALIAS = { purpur: 'purpur_block', quartz: 'quartz_block' };

// Wood shapes: the same handful of forms repeated for every tree, plus the two nether fungi and bamboo.
const WOOD_SHAPES = [
  [/^stripped_(.+)_log$/, 'green', '削掉树皮的原木，露出的木色浅一些，也更光滑。',
    'Bark shaved off a log, leaving paler wood and a smoother face.'],
  [/^stripped_(.+)_wood$/, 'green', '六面都削过的木头，颜色比带皮的浅。',
    'Wood stripped on all six faces, paler than it was with the bark on.'],
  [/^stripped_(.+)_stem$/, 'green', '削掉外皮的菌柄，里面是干的纤维。',
    'A stalk with its outer skin shaved off, dry fibre underneath.'],
  [/^stripped_(.+)_hyphae$/, 'green', '削过皮的菌核，颜色比原来匀净。',
    'Hyphae stripped of their skin, and even in colour afterwards.'],
  [/^(.+)_log$/, 'green', '带皮的原木，切面上还能数出年轮。',
    'A log with the bark still on; you can count the rings on the cut end.'],
  [/^(.+)_wood$/, 'green', '六面都是树皮的木段，拿来当柱子最像树。',
    'A length of wood with bark on all six faces, closest thing to a standing trunk.'],
  [/^(.+)_stem$/, 'green', '下界菌类的粗柄，掰开是干裂的纤维。',
    'The thick stalk of a nether fungus — dry, stringy fibre inside.'],
  [/^(.+)_hyphae$/, 'green', '菌柄外面那层皮裹满了一圈，颜色比菌柄深。',
    "The stalk's own skin wrapped all the way round, a shade darker than the stem."],
  [/^(.+)_planks$/, 'green', '顺着纹理锯开的木板，钉子吃得住，做家具也够平整。',
    'Boards cut with the grain — they hold a nail and stay flat enough for furniture.'],
  [/^(.+)_slab$/, 'green', '锯成半格的木板，铺地板省料，踩上去也不响。',
    'Plank sawn to half height: cheaper flooring, and it does not creak.'],
  [/^(.+)_stairs$/, 'green', '钉出来的木楼梯，上下楼比搭脚手架省事。',
    'A stair nailed together from boards, easier than scaffolding for a climb.'],
  [/^(.+)_fence$/, 'green', '木条钉成的围栏，能看见对面，但跨不过去。',
    'Rails nailed into a fence — see-through, not walk-through.'],
  [/^(.+)_fence_gate$/, 'green', '围栏上留的一道门，栓上之后和围栏一样拦人。',
    'A gate left in a fence line; latched, it holds as well as the rails do.'],
  [/^(.+)_door$/, 'green', '两格高的木门，关上之后怪进不来——前提是你记得关。',
    'A door two blocks tall that keeps things out, provided you remember to shut it.'],
  [/^(.+)_trapdoor$/, 'green', '平开的木活板门，合上是地板，掀开是洞口。',
    'A flat wooden hatch: floor when it is shut, hole when it is not.'],
  [/^(.+)_hanging_sign$/, 'green', '用铁链吊起来的木牌，底下不占地方。',
    'A board hung from chains, so the ground underneath stays clear.'],
  [/^(.+)_sign$/, 'green', '钉在墙上或者插在地上的木牌，字得自己写。',
    'A board for a wall or a post; whatever it says is up to you.'],
];

// Copper: eight forms, four patina stages, each optionally waxed to freeze the stage in place.
const COPPER_SHAPES = [
  ['chiseled_copper', '錾出浅纹的铜面', 'copper with a shallow pattern chiselled into the face'],
  ['cut_copper_slab', '切成半格的铜块', 'copper cut down to half height'],
  ['cut_copper_stairs', '带踏步的铜块', 'copper stepped into a stair'],
  ['cut_copper', '切出棱线的铜块', 'copper cut to a clean edge'],
  ['copper_grate', '铜条编成的格栅', 'copper bars woven into a grate'],
  ['copper_door', '铜板拼的门，比木门沉得多', 'a door of copper plate, far heavier than a wooden one'],
  ['copper_trapdoor', '铜板做的活板门，合上几乎看不出缝', 'a copper hatch that shows almost no seam when shut'],
  ['copper_block', '浇成一整块的铜', 'copper poured into a single block'],
];
const COPPER_STATES = {
  '': ['颜色还偏橙，放久了会慢慢发绿', 'still orange in tone, and it will green with time'],
  exposed_: ['表面开始发暗，边角浮出一点绿', 'the surface dulling, a little green at the edges'],
  weathered_: ['铜绿已经铺开大半', 'patina spread over most of it'],
  oxidized_: ['整块锈成了青绿', 'rusted green right through'],
};

const ORE_MINERALS = {
  coal: ['dark_gray', '煤', 'Coal'],
  copper: ['white', '铜', 'Copper'],
  iron: ['white', '铁', 'Iron'],
  gold: ['yellow', '金', 'Gold'],
  redstone: ['red', '红石粉', 'Redstone'],
  lapis: ['blue', '青金石', 'Lapis lazuli'],
  diamond: ['aqua', '钻石', 'Diamond'],
  emerald: ['green', '绿宝石', 'Emerald'],
  quartz: ['white', '石英', 'Quartz'],
};

// A few items whose shape suffix would give them the wrong sentence.
const OVERRIDE = {
  iron_bars: ['white', '铁条焊成的栅栏，看得过去，人过不去。', 'Iron bars welded into a grille: see through it, not past it.'],
  iron_door: ['white', '铁板拼的门，空手推不动，得靠按钮或者拉杆。', 'A door of iron plate that no bare hand will open — it wants a button or a lever.'],
  iron_trapdoor: ['white', '铁做的活板门，同样得靠红石才能掀开。', 'An iron hatch, and like the door it only lifts on a redstone signal.'],
  nether_brick_fence: ['dark_red', '下界砖砌的栅栏，比木栅栏耐火得多。', 'A fence of nether brick, far less willing to burn than a wooden one.'],
  stripped_bamboo_block: ['green', '削去外皮的竹块，颜色比带皮的浅，也更平整。', 'A bamboo block with its skin shaved off, paler and flatter than before.'],
};

function copperMatch(bare) {
  const waxed = bare.startsWith('waxed_');
  const body = waxed ? bare.slice('waxed_'.length) : bare;
  const stateKey = Object.keys(COPPER_STATES).find(key => key === '' ? !/^(exposed|weathered|oxidized)_/.test(body) : body.startsWith(key));
  const rest = body.slice(stateKey.length);
  const shape = COPPER_SHAPES.find(([token]) => token === rest
    || (token === 'copper_block' && stateKey !== '' && rest === 'copper'));
  if (!shape) return null;
  const [, zhShape, enShape] = shape;
  const [zhState, enState] = COPPER_STATES[stateKey];
  const zhTail = waxed ? '——上过蜡，就停在这一步' : '';
  const enTail = waxed ? ' — waxed, so it stops here' : '';
  return {
    color: 'white',
    zh: `${zhShape}，${zhState}${zhTail}。`,
    en: `${enShape.charAt(0).toUpperCase()}${enShape.slice(1)}, ${enState}${enTail}.`,
  };
}

function lore(bare) {
  if (bare in OVERRIDE) {
    const [color, zh, en] = OVERRIDE[bare];
    return { color, zh, en };
  }
  const copper = copperMatch(bare);
  if (copper) return copper;

  const netherOre = bare.match(/^nether_(gold|quartz)_ore$/);
  if (netherOre) {
    const [, mineral] = netherOre;
    const [color, zhMineral] = ORE_MINERALS[mineral];
    return {
      color,
      zh: mineral === 'gold'
        ? '下界岩里稀疏的金，一镐下去能敲出几粒。'
        : '嵌在下界岩里的石英，敲开是一片干净的白。',
      en: mineral === 'gold'
        ? 'Gold scattered thin through netherrack; one swing knocks out a few nuggets.'
        : 'Quartz held in netherrack, which splits into something very white.',
    };
  }

  const ore = bare.match(/^(deepslate_)?([a-z]+)_ore$/);
  if (ore && ORE_MINERALS[ore[2]]) {
    const [, deepslate, mineral] = ore;
    const [color, zhMineral, enMineral] = ORE_MINERALS[mineral];
    return deepslate
      ? { color, zh: `${zhMineral}长在了深层岩里，比上层石头里的更难敲出来。`, en: `${enMineral} grown into deepslate, and harder work to free than the sort above it.` }
      : ['iron', 'gold', 'copper'].includes(mineral)
        ? { color, zh: `石头里嵌着的${zhMineral}矿粒，敲下来还得送进炉子。`, en: `${enMineral} grains are locked in stone and need smelting after they are mined.` }
        : { color, zh: `石头里嵌着清楚的${zhMineral}矿脉，用合适的镐敲开就能收取。`, en: `A clear ${enMineral.toLowerCase()} seam crosses the stone and yields its material under a suitable pick.` };
  }

  for (const [pattern, color, zh, en] of WOOD_SHAPES) {
    const match = bare.match(pattern);
    if (match && WOODS.includes(match[1])) return { color, zh: `${WOOD_DETAIL[match[1]][0]}${zh}`, en: `${WOOD_DETAIL[match[1]][1]} ${en}` };
  }

  const shaped = bare.match(/^(.+)(_slab|_stairs|_wall)$/);
  if (shaped && shaped[2] in SHAPE_LORE) {
    const stem = STEM_ALIAS[shaped[1]] ?? shaped[1];
    const material = BASE[stem] ?? BASE[`${stem}s`];
    if (material) {
      const [zh, en] = SHAPE_LORE[shaped[2]];
      const materialZh = material[1] || `${displayName('zh_cn', bare)}的材质紧实，棱边平整。`;
      const materialEn = material[2] || `The ${displayName('en_us', bare).toLowerCase()} have close-grained material and even edges.`;
      return { color: material[0], zh: `${materialZh}${zh}`, en: `${materialEn} ${en}` };
    }
  }

  if (bare in BASE) {
    const [color, zh, en] = BASE[bare];
    return { color, zh, en };
  }
  return null;
}

const entries = [];
const unmatched = [];
for (const bare of categoryIds('building_blocks')) {
  if (isAuthored(bare)) continue;
  const found = lore(bare);
  if (!found) { unmatched.push(bare); continue; }
  entries.push({
    id: bare, rarity: 0, color: found.color,
    zh: { name: displayName('zh_cn', bare), lore: found.zh },
    en: { name: displayName('en_us', bare), lore: found.en },
  });
}

if (unmatched.length) {
  console.error(`UNMATCHED (${unmatched.length}): ${unmatched.join(', ')}`);
  process.exit(1);
}
console.log(`building_blocks: emitted ${emit('building_blocks_gen', entries)} entries`);
