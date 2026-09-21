# render_card 主题与参数对照（md2card）

> render_card 选主题 / theme_mode / 尺寸前，先 read_file 本文件对照下表选择；
> 用户未指定风格时旅行攻略默认 `xiaohongshu`。主题 ID 也可传自定义主题的 CSS class 名。

## 主题全表（23 套）

「模式」列有值的主题才支持 `theme_mode` 参数，其余传了会被忽略。

| theme | 中文名 | 可选 theme_mode | 适用场景与风格 |
|---|---|---|---|
| `apple-notes` | 苹果备忘录 | `light-mode` / `dark-mode` | 简约清晰，苹果备忘录质感，日常笔记与通用分享 |
| `coil-notebook` | 线圈笔记本 | `blue-mode` / `pink-mode` / `mint-mode` / `yellow-mode` | 线圈本物理外观，学习笔记、课堂记录、手账 |
| `pop-art` | 波普艺术 | `default-mode` / `pink-blue-mode` / `mint-mode` / `purple-mode` | 色彩对比强烈，漫画复古海报，创意设计、潮流文化 |
| `bytedance` | 字节范 | — | 现代科技感，蓝红渐变，技术内容、产品介绍 |
| `alibaba` | 阿里橙 | — | 橙色主调，电商、营销活动、品牌宣传 |
| `art-deco` | 艺术装饰 | — | 1920 年代复古几何华丽感，高端品牌、复古主题 |
| `glassmorphism` | 玻璃拟态 | — | 毛玻璃半透明轻盈风，渐变背景内容展示 |
| `warm` | 温暖柔和 | — | 色调温暖柔和，生活分享、情感随笔、个人博客 |
| `minimal` | 简约高级灰 | — | 灰调专业商务，商业报告、行业分析 |
| `minimalist` | 极简黑白 | — | 纯粹黑白，名言警句、哲学思考、严肃文学摘录 |
| `dreamy` | 梦幻渐变 | — | 柔和渐变色彩，艺术创作、灵感分享 |
| `nature` | 清新自然 | — | 绿色环保色调，生活方式、健康饮食、旅行游记 |
| `xiaohongshu` | 紫色小红书 | — | 专为小红书优化，时尚年轻，美妆、穿搭、探店图文 |
| `notebook` | 笔记本 | — | 通用笔记风格，布局规整，结构化笔记、知识卡片 |
| `darktech` | 暗黑科技 | — | 深色背景科技元素，编程教程、技术文档 |
| `typewriter` | 复古打字机 | — | 老式打字机质感，诗歌、小说片段、怀旧主题 |
| `watercolor` | 水彩艺术 | — | 水彩晕染效果，插画配文、文艺内容 |
| `traditional-chinese` | 中国传统 | — | 传统纹理与配色，国学、诗词、传统文化、节日祝福 |
| `fairytale` | 儿童童话 | — | 色彩明快可爱，儿童故事、亲子内容 |
| `business` | 商务简报 | — | 专业严谨，职场演示、商业提案 |
| `japanese-magazine` | 日本杂志 | — | 日系排版多留白，时尚、设计、生活方式 |
| `cyberpunk` | 赛博朋克 | — | 霓虹未来感，游戏、科幻、未来科技 |
| `meadow-dawn` | 青野晨光 | — | 清晨柔和色调，励志、成长、清新文艺 |

## 尺寸预设（type 参数，指定后忽略 width/height）

| type | 尺寸（宽×高） | 适用场景 |
|---|---|---|
| `xiaohongshu` | 440×586 | 小红书图文笔记（约 3:4） |
| `square` | 500×500 | 社交平台通用方图 |
| `poster` | 440×782 | 手机端长图海报 |
| `a4` | 595×842 | A4 纸打印输出 |

也可直接传 `width`/`height`（px，200-2000）。

## 拆卡模式（split_mode）

- `noSplit`（默认，**推荐**）：单卡输出——一份内容一张长图卡，高度随内容自适应，不传 split_mode 即可。
- `hrSplit`：按 markdown 的 `---` 分隔线拆成多卡——**仅当用户明确要求多张拆分卡时才用**，注意 markdown 里现成的 `---` 都会成为切分点。
- `autoSplit`：按高度自动分割，慢，不建议。

## 其他

- `mdx_mode: true` 启用 MDX（JSX/自定义字体/公式/Mermaid 图表）。
- 服务端超时 60s，首次调用可能较慢；每次调用消耗 md2card 积分。
