package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.FactsStore;
import org.example.Skill;
import org.example.SkillSchema;
import org.example.SkillState;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * record_facts：把检索到的关键数据写入事实账本（维度×时期×口径 → 值/来源/状态）。
 * 账本是最终答案的结算依据——压缩与校验都以它为准，未入账的数据随时可能随上下文丢失。
 */
public class RecordFactsTool implements AgentTool {

    private static final Set<String> STATUSES = Set.of("found", "proxy", "not_found");
    private static final ObjectMapper M = new ObjectMapper();
    private final FactsStore facts;
    private final SkillState skills;

    public RecordFactsTool(FactsStore facts, SkillState skills) {
        this.facts = facts;
        this.skills = skills;
    }

    @Override
    public String name() {
        return "record_facts";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "把检索到的关键数据写入事实账本。每得到一条可用数据就立即入账"
                        + "（不要攒到最后批量补）；最终答案的全部数据必须来自账本。"
                        + "回执区分新增/覆盖更新/无变化——无变化=数据已在账本，直接复用，不要重复检索。"
                        + "status: found=官方或已交叉核验；proxy=代理指标/第三方折算（note 写折算方法）；"
                        + "not_found=确认检索不到（note 必须写明已尝试的检索关键词与来源，否则视为放弃过早）。"
                        + "会影响主计划成立的关键依赖（如预约、库存、通行、营业状态）应传 critical=true；"
                        + "critical 且非 found 时，note 必须同时写「保守主方案：」和「升级条件：」。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "facts", Map.of("type", "array", "description", "本次入账的事实数组",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "dimension", Map.of("type", "string",
                                                                "description", "指标维度，如 GDP增速、常住人口"),
                                                        "period", Map.of("type", "string",
                                                                "description", "时期，如 2024、2024-Q1、2024-H1、2024-Q1-3（前三季度）"),
                                                        "metric", Map.of("type", "string",
                                                                "description", "统计口径，如 规模以上工业；单一口径可留空"),
                                                        "value", Map.of("type", "string",
                                                                "description", "短摘要，如 34606亿元、+5.1%。传 payload 时建议省略或不超过80字符，不要重复 payload 全文；not_found 留空"),
                                                        "source", Map.of("type", "string",
                                                                "description", "来源链接或来源名；not_found 可留空"),
                                                        "status", Map.of("type", "string",
                                                                "enum", List.of("found", "proxy", "not_found"),
                                                                "description", "数据状态"),
                                                        "note", Map.of("type", "string",
                                                                "description", "口径说明/折算方法；not_found 时写已尝试的检索关键词与来源"),
                                                        "critical", Map.of("type", "boolean",
                                                                "description", "该事实是否为方案成立的关键依赖。非 found 的关键依赖必须在 note 写「保守主方案：」与「升级条件：」"),
                                                        "schema", Map.of("type", "string",
                                                                "description", "payload 使用的技能数据结构引用。优先用 $id#/$defs/<定义名> 指向具体对象；根 $id 仅当 payload 本身是完整 envelope；仅当传 payload 时必填"),
                                                        "payload", Map.of("type", "object",
                                                                "description", "结构化领域事实对象。按已加载 Skill 的 JSON Schema 组织；未覆盖字段放入 payload.extensions；不要与 value/note 大段重复；evidence 来源语义必须符合 Schema")),
                                                "required", List.of("dimension", "period", "status")))),
                        "required", List.of("facts")));
    }

    @Override
    public String execute(JsonNode input) {
        JsonNode arr = input.path("facts");
        if (!arr.isArray() || arr.isEmpty()) {
            return "参数 facts 必须是非空数组，每项含 dimension、period、status（可选 metric/value/source/note）";
        }
        List<String> rejected = new ArrayList<>();
        List<String> offTarget = new ArrayList<>();
        List<String> offDimension = new ArrayList<>();
        List<String> appendWarnings = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (JsonNode n : arr) {
            String dimension = ToolRegistry.optStr(n, "dimension");
            String period = ToolRegistry.optStr(n, "period");
            String status = normalize(ToolRegistry.optStr(n, "status"));
            String note = ToolRegistry.optStr(n, "note");
            boolean critical = n.path("critical").asBoolean(false);
            if (dimension == null || period == null) {
                rejected.add("缺 dimension 或 period: " + abbreviate(n));
                continue;
            }
            if (status == null) {
                rejected.add("status 无效（可选 found/proxy/not_found）: " + abbreviate(n));
                continue;
            }
            JsonNode payload = n.get("payload");
            String payloadSchema = ToolRegistry.optStr(n, "schema");
            String structuredValue = null;
            if (payload != null && !payload.isNull()) {
                if (!payload.isObject()) {
                    rejected.add("[" + dimension + "@" + period + "] payload 必须是 object");
                    continue;
                }
                String plainValue = ToolRegistry.optStr(n, "value");
                if (plainValue != null && plainValue.strip().length() > 240) {
                    rejected.add("[" + dimension + "@" + period + "] 同时传 payload 时 value 只能是短摘要（≤240字符），不要重复结构化正文");
                    continue;
                }
                String schemaError = schemaError(payloadSchema);
                if (schemaError != null) {
                    rejected.add("[" + dimension + "@" + period + "] " + schemaError);
                    continue;
                }
                SkillSchema declared = declaredSchema(payloadSchema);
                String badFragment = missingFragmentError(payloadSchema, declared);
                if (badFragment != null) {
                    rejected.add("[" + dimension + "@" + period + "] " + badFragment);
                    continue;
                }
                Skill currentSkill = skills.get();
                List<String> validationErrors = SkillSchema.validate(payloadSchema, payload, currentSkill.schemas());
                if (!validationErrors.isEmpty()) {
                    rejected.add("[" + dimension + "@" + period + "] payload 不符合 schema "
                            + payloadSchema + ": " + String.join("; ", validationErrors)
                            + "; " + repairHint(payloadSchema, declared));
                    continue;
                }
                try {
                    structuredValue = M.writeValueAsString(payload);
                } catch (Exception e) {
                    rejected.add("[" + dimension + "@" + period + "] payload JSON 序列化失败: " + e.getMessage());
                    continue;
                }
            }
            if ("not_found".equals(status) && (note == null || note.isBlank())) {
                rejected.add("[" + dimension + "@" + period + "] not_found 必须在 note 写明已尝试的检索关键词与来源");
                continue;
            }
            if (critical && !"found".equals(status) && (note == null
                    || !note.contains("保守主方案：") || !note.contains("升级条件："))) {
                rejected.add("[" + FactsStore.canonicalDimension(dimension) + "@" + period
                        + "] 是关键依赖但状态为 " + status
                        + "，必须在 note 同时写明「保守主方案：」与「升级条件：」，不得让它直接支撑主方案");
                continue;
            }
            // 三态计数：无变化=重复入账既有数据，回执点名提示——给模型即时的「勿重复检索」负反馈
            String metric = orEmpty(ToolRegistry.optStr(n, "metric"));
            switch (facts.record(new FactsStore.Fact(dimension, period,
                    metric,
                    structuredValue == null ? orEmpty(ToolRegistry.optStr(n, "value")) : structuredValue,
                    orEmpty(ToolRegistry.optStr(n, "source")),
                    status, orEmpty(note), null, critical))) {
                case NEW -> added++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
            List<String> related = facts.otherMetrics(dimension, period, metric);
            if (!related.isEmpty()) {
                appendWarnings.add(dimension + "@" + period + " 本次 metric=" + metric
                        + "；已有不同 metric=" + String.join("、", related));
            }
            // 两类标签错位提示互斥：dimension 精确命中才校验 period；未命中则查近似目标（对称提示）
            if (!facts.hitsTarget(dimension, period)) {
                offTarget.add(dimension + "@" + period);
            } else {
                List<String> near = facts.nearTargets(dimension);
                if (!near.isEmpty()) {
                    offDimension.add(dimension + "（疑似应为 " + String.join("、", near) + "）");
                }
            }
        }
        int accepted = added + updated + unchanged;
        StringBuilder sb = new StringBuilder("已入账 ").append(accepted).append(" 条（新增 ").append(added)
                .append("、覆盖更新 ").append(updated).append("、无变化 ").append(unchanged).append("）");
        if (unchanged > 0) {
            sb.append("\n提示: ").append(unchanged).append(" 条与账本现有条目完全相同——该数据已在账本，")
                    .append("直接复用即可，不要为同一数据重复检索或重复入账");
        }
        if (!rejected.isEmpty()) {
            sb.append("；被拒绝 ").append(rejected.size()).append(" 条:\n").append(String.join("\n", rejected));
        }
        if (!offTarget.isEmpty()) {
            sb.append("\n注意: ").append(offTarget.size())
                    .append(" 条的 dimension 已有覆盖目标、但 period 不在目标时期内（")
                    .append(String.join("、", offTarget))
                    .append("）——若这正是目标数据，请照抄覆盖目标的 period 字符串重新入账（同 key 覆盖旧值），否则终答覆盖闸门将报缺口");
        }
        if (!offDimension.isEmpty()) {
            sb.append("\n注意: ").append(offDimension.size())
                    .append(" 条的 dimension 未精确命中覆盖目标、但与其近似（")
                    .append(String.join("、", offDimension))
                    .append("）——若这正是目标数据，请照抄覆盖目标的 dimension 字符串重新入账（同 key 覆盖旧值），否则终答覆盖闸门将报缺口");
        }
        if (!appendWarnings.isEmpty()) {
            sb.append("\n注意: ").append(appendWarnings.size())
                    .append(" 条会作为新增口径并存而非覆盖旧口径（")
                    .append(String.join("；", appendWarnings))
                    .append("）。若目的是覆盖更新，必须完全复用旧条目的 dimension/period/metric；")
                    .append("若确为新口径，请在 note 说明与旧口径的关系，避免终答误用旧值");
        }
        return sb.append('\n').append(facts.coverageLine()).toString();
    }

    /** payload 必须声明当前 Skill 已加载的数据契约；基座不解释 Schema 内容。 */
    private String schemaError(String schema) {
        if (schema == null || schema.isBlank()) {
            return "传 payload 时必须同时传 schema（$id 或 $id#/$defs/<定义名>）";
        }
        Skill skill = skills.get();
        if (skill == null) {
            return "尚未加载 Skill，不能使用 payload";
        }
        if (skill.schemas().stream().anyMatch(s -> s.supports(schema))) {
            return null;
        }
        String declared = skill.schemas().stream().map(SkillSchema::id).collect(Collectors.joining("、"));
        return "schema 引用未在当前 Skill 的 schemas/*.json 中声明: " + schema
                + ";可声明的引用: " + declared;
    }

    /** 按 supports 语义定位 payload 引用声明的 Schema（调用前已通过 schemaError 确认存在）。 */
    private SkillSchema declaredSchema(String schemaRef) {
        return skills.get().schemas().stream()
                .filter(s -> s.supports(schemaRef)).findFirst().orElse(null);
    }

    /**
     * 子定义引用存在性自查。SkillSchema.resolve 对不存在的 #/$defs/&lt;名&gt; 返回 MissingNode、
     * validateNode 随即跳过校验——拼错的定义名会静默通过校验；这里先把拼错拦下并给出可用清单。
     */
    private static String missingFragmentError(String schemaRef, SkillSchema schema) {
        if (schema == null) {
            return null;
        }
        int hash = schemaRef.indexOf('#');
        if (hash < 0 || !schemaRef.substring(hash).startsWith("#/")) {
            return null;
        }
        JsonNode def = resolveFragment(schema, schemaRef.substring(hash));
        if (!def.isMissingNode()) {
            return null;
        }
        String defs = defsCatalog(schema);
        return "schema 引用了不存在的子定义 " + schemaRef.substring(hash)
                + (defs.isEmpty() ? "（该 Schema 无 $defs，引用根 $id 即可）" : ";可用子定义: " + defs);
    }

    /**
     * 校验失败的修复路径——错误信息必须可直接照抄，否则模型会放弃 payload、
     * 退化为纯 value/note 文本入账（领域结构随之丢失）：
     * 根引用 → 给出子定义清单及各自 required，或无子定义时指引原样搬运完整 envelope；
     * 子定义引用 → 给出该定义的 required 与 Schema 文件路径，供补齐字段或 read_file 核对。
     */
    private static String repairHint(String schemaRef, SkillSchema schema) {
        if (schema == null) {
            return "请核对 schema 引用后重发本条 payload，不要退化为纯 value/note 文本入账";
        }
        StringBuilder sb = new StringBuilder("修复路径: ");
        int hash = schemaRef.indexOf('#');
        String fragment = hash < 0 ? "" : schemaRef.substring(hash);
        if (fragment.isEmpty() || "#".equals(fragment)) {
            String defs = defsCatalog(schema);
            if (defs.isEmpty()) {
                sb.append("该 Schema 无子定义，payload 须为其完整 envelope——从技能数据文件原样搬运对应结构、")
                        .append("补齐根字段（").append(requiredList(schema.root())).append("）与上述缺失字段，不要手工精简字段");
            } else {
                sb.append("外层已有 dimension/period/status 时，payload 通常应改用子定义引用、只传该子对象；可用子定义: ")
                        .append(defs);
            }
        } else {
            sb.append("按 ").append(fragment).append(" 的 ").append(requiredList(resolveFragment(schema, fragment)))
                    .append("补齐上述缺失字段，不要手工精简字段");
        }
        return sb.append(";完整定义可 read_file ").append(schema.source())
                .append(";修复后重发本条 payload，不要退化为纯 value/note 文本入账").toString();
    }

    /** 子定义目录："#/$defs/名(required: a, b)" 列表，供错误信息直接照抄。 */
    private static String defsCatalog(SkillSchema schema) {
        JsonNode defs = schema.root().path("$defs");
        if (!defs.isObject() || defs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        defs.fields().forEachRemaining(e -> {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("#/$defs/").append(e.getKey()).append("（").append(requiredList(e.getValue())).append("）");
        });
        return sb.toString();
    }

    private static String requiredList(JsonNode def) {
        JsonNode required = def == null ? null : def.path("required");
        if (required == null || !required.isArray() || required.isEmpty()) {
            return "无必填字段";
        }
        List<String> names = new ArrayList<>();
        required.forEach(k -> names.add(k.asText()));
        return "required: " + String.join(", ", names);
    }

    /** 与 SkillSchema.resolve 同语义的 fragment 路径解析（含 ~0/~1 转义）。 */
    private static JsonNode resolveFragment(SkillSchema schema, String fragment) {
        JsonNode cur = schema.root();
        for (String part : fragment.substring(2).split("/")) {
            cur = cur.path(part.replace("~1", "/").replace("~0", "~"));
        }
        return cur;
    }

    /** 状态归一：空白默认 found（大多数入账是刚检索到的可用数据）。 */
    private static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "found";
        }
        String s = status.strip();
        return STATUSES.contains(s) ? s : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    private static String abbreviate(JsonNode n) {
        String one = n.toString().replaceAll("\\s+", " ");
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
