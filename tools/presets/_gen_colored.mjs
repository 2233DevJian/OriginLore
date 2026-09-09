// Generates tools/presets/{zh_cn,en_us}/colored_blocks.json — 16 dyes x 13 shapes plus the three undyed originals.
// Display names come from the vanilla language files; only the lore is authored, once per shape.
import { init, displayName, categoryIds, isAuthored, emit } from './_lib.mjs';

init();

const COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
const COLOR_DETAIL = {
  white: ['白色干净，适合衬亮暗处。', 'Clean white brings contrast to a dark corner.'],
  orange: ['橙色明亮，远处也容易认清。', 'Bright orange is easy to recognise at a distance.'],
  magenta: ['品红浓艳，少量就很醒目。', 'Strong magenta stands out even in a small amount.'],
  light_blue: ['浅蓝清淡，和白色并排很协调。', 'Pale blue sits easily beside white.'],
  yellow: ['黄色鲜明，做标记很容易找。', 'Clear yellow makes an easy marker to find.'],
  lime: ['黄绿色鲜嫩，夹在深色中很突出。', 'Fresh lime green stands out among darker colours.'],
  pink: ['粉色柔和，铺开也不显沉重。', 'Soft pink stays light across a broad area.'],
  gray: ['灰色低沉，适合收住过亮的配色。', 'Muted grey steadies a brighter arrangement.'],
  light_gray: ['浅灰平和，用在边线处不抢眼。', 'Quiet light grey marks an edge without drawing the eye.'],
  cyan: ['青色偏冷，和石材的暗面很相称。', 'Cool cyan sits well beside shaded stone.'],
  purple: ['紫色厚重，少量就能定出轮廓。', 'Deep purple defines an outline with little material.'],
  blue: ['蓝色浓而清楚，适合标出远处的位置。', 'Strong blue clearly marks a distant position.'],
  brown: ['棕色接近木纹，放在木屋里很自然。', 'Brown sits naturally alongside timber grain.'],
  green: ['绿色深而稳，和叶丛并排不突兀。', 'Deep green sits quietly beside foliage.'],
  red: ['红色饱满，做警示或边饰都醒目。', 'Full red stands out as a warning or a border.'],
  black: ['黑色压得很实，适合勾出清楚的边界。', 'Solid black gives a boundary a clear outline.'],
};

// Ordered longest-suffix-first so concrete_powder and stained_glass_pane are not swallowed by their shorter twins.
const SHAPES = [
  ['stained_glass_pane', 0, 'white',
    '薄玻璃片，装窗比整块省料，也更容易碎。',
    'Thin panes — cheaper than full blocks for glazing a window, and quicker to break.'],
  ['concrete_powder', 0, 'white',
    '装袋的粉末，沾水就凝住，别让它淋着雨。',
    'Bagged powder that stiffens the moment water touches it; keep it out of the rain.'],
  ['glazed_terracotta', 0, 'white',
    '窑里烧出来的釉面，转个角度能看见光在走。',
    'Glaze fired in a kiln; turn it a little and you can watch the light travel.'],
  ['shulker_box', 1, 'white',
    '两片潜影壳夹着一只箱子，敲碎了里面的东西也不会掉出来。',
    'A chest held between two shulker shells — break it and nothing inside spills out.'],
  ['stained_glass', 0, 'white',
    '颜料熔进了玻璃里，透光，但不透景。',
    'Pigment melted into the glass: light comes through, the view does not.'],
  ['concrete', 0, 'white',
    '粉末遇水凝成的硬块，敲上去声音发闷。',
    'Powder set hard by water — it rings dull under a pick.'],
  ['terracotta', 0, 'white',
    '黏土入窑烧成的素坯，颜色沉得住，不怕日晒。',
    'Clay fired into a plain body that holds its colour and does not mind the sun.'],
  ['carpet', 0, 'white',
    '薄薄一层羊毛，铺在地上能把脚步声压下去。',
    'A thin layer of wool that takes the noise out of footsteps.'],
  ['banner', 0, 'white',
    '布面上还留着织机的压痕，图案得自己绣。',
    "The cloth still carries the loom's press marks; the emblem is yours to work in."],
  ['candle', 0, 'yellow',
    '蜂蜡裹住一段细绳，点亮之后能烧很久。',
    'Beeswax drawn around a length of string; once lit it burns a long while.'],
  ['wool', 0, 'white',
    '剪下来洗净再压实的羊毛，按上去还是软的。',
    'Wool sheared, washed and pressed — it still gives under the hand.'],
  ['dye', 0, 'white',
    '磨细的颜料，染羊毛、染陶瓦、染玻璃都用得上。',
    'Ground pigment, good for wool, terracotta and glass alike.'],
  ['bed', 0, 'white',
    '木板和羊毛拼的床，够一个人睡；放哪儿都行，别挡着门。',
    'Planks and wool make a bed for one; put it anywhere, just not across a doorway.'],
];

// The three items that exist without any dye applied.
const PLAIN = {
  candle: [0, 'yellow', '没上色的蜡烛，还是蜂蜡本来的黄白。', 'An undyed candle, still the yellow-white of the wax itself.'],
  terracotta: [0, 'white', '没上釉的陶瓦，出窑时就是这个颜色。', 'Unglazed terracotta, the colour it comes out of the kiln with.'],
  shulker_box: [1, 'white', '原色的潜影盒，两片壳夹一只箱子，搬家不用先卸货。',
    'A plain shulker box: two shells around a chest, so you can move house without unpacking.'],
};

const shapeBySuffix = new Map(SHAPES.map(shape => [shape[0], shape]));
const entries = [];
const unmatched = [];

for (const bare of categoryIds('colored_blocks')) {
  if (isAuthored(bare)) continue;
  const dye = COLORS.find(color => bare.startsWith(`${color}_`));
  if (dye) {
    const shape = shapeBySuffix.get(bare.slice(dye.length + 1));
    if (!shape) { unmatched.push(bare); continue; }
    const [, rarity, color, zh, en] = shape;
    entries.push({
      id: bare, rarity, color,
      zh: { name: displayName('zh_cn', bare), lore: `${zh}${COLOR_DETAIL[dye][0]}` },
      en: { name: displayName('en_us', bare), lore: `${en} ${COLOR_DETAIL[dye][1]}` },
    });
    continue;
  }
  const plain = PLAIN[bare];
  if (!plain) { unmatched.push(bare); continue; }
  const [rarity, color, zh, en] = plain;
  entries.push({
    id: bare, rarity, color,
    zh: { name: displayName('zh_cn', bare), lore: zh },
    en: { name: displayName('en_us', bare), lore: en },
  });
}

if (unmatched.length) {
  console.error(`UNMATCHED (${unmatched.length}): ${unmatched.join(', ')}`);
  process.exit(1);
}
console.log(`colored_blocks: emitted ${emit('colored_blocks', entries)} entries`);
