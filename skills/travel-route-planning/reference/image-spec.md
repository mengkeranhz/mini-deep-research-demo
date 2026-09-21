# 图片与排版规范（可选增强·详细版）

> 本文件是 SKILL.md「图片与排版（可选增强）」的展开模板，仅在决定配图时读取。
> 前提不变：图片不承载关键信息，价格/时间/政策/预约状态必须文字复述；图源必须来自事实账本或攻略卡。

## 1. 尺寸分级：一律小巧

| 用途 | 推荐宽度 | 说明 |
|---|---:|---|
| 表格内缩略图 | 56—88px | 首选，能塞进单元格就塞 |
| 表格内小图 + 图注 | 72—96px | 图注用 10—11px 小字 |
| 行内小图 | 120—180px | 放段落旁边或列表里 |
| 正文配图 | 240—320px | 不要超过 360px |
| 封面/路线图 | 400—520px | 最大不超过 560px |
| 并排小图 | 每张 96—140px | 最多 2—3 张 |

所有 `<img>` 必须带：

```html
max-width:100%; height:auto;
```

表格内图片必须额外控制宽度，推荐 `width="72"` 或 `width="88"`。

## 2. 表格融合：首选方案

**能进表格的图，就不要单独占一段。**  
推荐在每日行程表、食宿表、风险表中增加一列「图」，宽度控制在 88px 以内。

表格外层建议包一层滚动容器，防止移动端撑破：

```html
<div style="overflow-x:auto;">
  <table>
    ...
  </table>
</div>
```

**表格内缩略图模板：**

```html
<td style="text-align:center; min-width:88px; vertical-align:middle;">
  <img src="图片URL" alt="图片描述" width="72"
       style="display:block; margin:0 auto 4px; max-width:100%; height:auto;
              border-radius:8px; border:1px solid #eee;">
  <span style="display:block; font-size:10px; color:#999; line-height:1.2;">
    图1
  </span>
</td>
```

**每日行程表推荐列：**

| 时间 | 活动安排 | 交通/耗时 | 费用 | 预约 | 图 |
|---|---|---|---|---|---|
| 09:00 | **XX景点**<br><span style="font-size:12px;color:#666;">一句话提示</span> | 步行 10min | ¥XX | ✅ 已约 | 缩略图 |

**单元格里图 + 文混排模板：**

```html
<td>
  <strong>XX景点</strong><br>
  <span style="font-size:12px; color:#666;">一句话提示</span><br>
  <img src="图片URL" alt="XX景点" width="72"
       style="display:block; margin:6px auto 4px; max-width:100%; height:auto;
              border-radius:8px; border:1px solid #eee;">
  <span style="display:block; font-size:10px; color:#999; text-align:center;">图1</span>
</td>
```

## 3. 正文小图模板

不要再用 640/720 的大图。正文配图推荐 240—320px：

```html
<img src="图片URL" alt="图片描述" width="280"
     style="display:block; margin:10px auto; max-width:100%; height:auto;
            border-radius:10px; border:1px solid #eee;">
```

封面图最大 520px：

```html
<img src="封面图URL" alt="XX目的地封面" width="480"
     style="display:block; margin:12px auto; max-width:100%; height:auto;
            border-radius:14px;">
```

并排小图：

```html
<div style="display:flex; gap:6px; flex-wrap:wrap; justify-content:center;">
  <img src="URL1" alt="描述1" width="140"
       style="max-width:100%; height:auto; border-radius:10px;">
  <img src="URL2" alt="描述2" width="140"
       style="max-width:100%; height:auto; border-radius:10px;">
</div>
```

## 4. 图注、来源与版权

表格内为了小巧，可以只写「图1」「图2」；在表格下方统一放「图片索引」：

| 图号 | 内容 | 来源 | 版权 | 核实日期 |
|---|---|---|---|---|
| 图1 | XX景点 | 来源链接/名称 | 可公开引用/仅参考 | YYYY-MM-DD |

正文大一点的图，图注紧贴图片下方：

```html
<div style="font-size:11px; color:#999; text-align:center; margin-top:-4px;">
  图：XX｜来源：XX｜版权：可公开引用/仅参考｜核实于 YYYY-MM-DD
</div>
```

版权不明必须标：**仅参考，未获授权，请勿二次传播**。

## 5. 硬规则

- 图片不能承载关键信息。价格、时间、政策、预约状态必须文字复述。
- 表格内每行最多 1 张图，每表最多 1 列图片。
- 全篇图片建议 ≤10 张；表格内缩略图不计入“大图”数量。
- 图片链接必须来自事实账本或攻略卡，注明来源、版权、核实日期。
- 平台不支持 HTML 时，降级为 Markdown 图片语法，并尽量选小尺寸缩略图；尺寸无法控制时，用 emoji + 文字排版，不硬塞图。
