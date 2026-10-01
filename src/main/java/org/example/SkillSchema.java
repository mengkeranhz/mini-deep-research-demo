package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技能数据结构：skills/&lt;name&gt;/schemas/*.json。
 * 基座只把 JSON Schema 当作可声明的数据契约加载与展示，不理解其中任何领域字段；
 * 领域对象、事实形态和校验语义全部由 Skill 提供。
 */
public record SkillSchema(String id, String content) {

    private static final ObjectMapper M = new ObjectMapper();

    /** 读取并做语法级校验；坏 Schema 在启动期失败，避免领域契约静默丢失。 */
    public static SkillSchema load(Path file) {
        try {
            JsonNode root = M.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isObject()) {
                throw new IllegalArgumentException("技能数据结构必须是 JSON object");
            }
            String id = root.path("$id").asText(root.path("id").asText(null));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("技能数据结构缺少 $id");
            }
            return new SkillSchema(id.strip(), root.toString());
        } catch (IOException e) {
            throw new IllegalStateException("读取技能数据结构失败: " + file + ": " + e.getMessage(), e);
        }
    }

    /** 是否能解析 payload 声明的 schema 引用（$id、$id#$defs/x 或 def 名称）。 */
    public boolean supports(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        return reference.strip().equals(id) || reference.strip().startsWith(id + "#");
    }

    /**
     * 按已加载的 JSON Schema 子集校验 payload。
     * 这是通用结构校验，不理解任何领域字段；支持 type/required/properties/additionalProperties/
     * items/minItems/maxItems/enum/const/minLength/maxLength/pattern/minimum/maximum/$ref。
     */
    public static List<String> validate(String reference, JsonNode payload,
                                        Collection<SkillSchema> schemas) {
        SkillSchema selected = null;
        String fragment = "#";
        if (reference != null) {
            String ref = reference.strip();
            for (SkillSchema schema : schemas) {
                if (schema.supports(ref)) {
                    selected = schema;
                    if (ref.startsWith(schema.id + "#")) {
                        fragment = ref.substring((schema.id + "#").length());
                    }
                    break;
                }
            }
        }
        if (selected == null) {
            return List.of("未找到 schema 引用: " + reference);
        }
        try {
            JsonNode root = M.readTree(selected.content);
            JsonNode node = resolve(root, fragment);
            List<String> errors = new ArrayList<>();
            validateNode(payload, node, root, schemas, "$", errors);
            return errors;
        } catch (Exception e) {
            return List.of("schema 校验失败: " + e.getMessage());
        }
    }

    private static JsonNode resolve(JsonNode root, String reference) {
        if (reference == null || reference.isBlank() || "#".equals(reference)) {
            return root;
        }
        if (!reference.startsWith("#/")) {
            return root;
        }
        JsonNode cur = root;
        for (String part : reference.substring(2).split("/")) {
            cur = cur.path(part.replace("~1", "/").replace("~0", "~"));
        }
        return cur;
    }

    private static void validateNode(JsonNode value, JsonNode schema, JsonNode root,
                                     Collection<SkillSchema> schemas, String path, List<String> errors) {
        if (schema.isMissingNode() || !schema.isObject()) {
            return;
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null && !ref.isNull()) {
            String reference = ref.asText();
            if (reference.startsWith("#")) {
                validateNode(value, resolve(root, reference), root, schemas, path, errors);
            } else {
                int hash = reference.indexOf('#');
                String schemaId = hash >= 0 ? reference.substring(0, hash) : reference;
                String fragment = hash >= 0 ? reference.substring(hash) : "#";
                schemas.stream()
                        .filter(s -> s.id.equals(schemaId))
                        .findFirst()
                        .ifPresentOrElse(
                                s -> {
                                    try {
                                        JsonNode externalRoot = M.readTree(s.content);
                                        validateNode(value, resolve(externalRoot, fragment), externalRoot,
                                                schemas, path, errors);
                                    } catch (Exception e) {
                                        errors.add(path + ": $ref 解析失败 " + reference);
                                    }
                                },
                                () -> errors.add(path + ": 未找到外部 schema " + schemaId));
            }
            return;
        }

        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(value, type.asText())) {
            errors.add(path + ": 类型应为 " + type.asText() + "，实际 " + jsonType(value));
            return;
        }
        JsonNode required = schema.get("required");
        if (value.isObject() && required != null && required.isArray()) {
            for (JsonNode key : required) {
                JsonNode child = value.get(key.asText());
                if (child == null || child.isNull()) {
                    errors.add(path + ": 缺少 required 字段 " + key.asText());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (value.isObject() && properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                JsonNode child = value.get(entry.getKey());
                if (child != null && !child.isNull()) {
                    validateNode(child, entry.getValue(), root, schemas,
                            path + "." + entry.getKey(), errors);
                }
            });
            JsonNode additional = schema.get("additionalProperties");
            if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                value.fields().forEachRemaining(entry -> {
                    if (!properties.has(entry.getKey())) {
                        errors.add(path + ": 不允许额外字段 " + entry.getKey());
                    }
                });
            } else if (additional != null && additional.isObject()) {
                value.fields().forEachRemaining(entry -> {
                    if (!properties.has(entry.getKey())) {
                        validateNode(entry.getValue(), additional, root, schemas,
                                path + "." + entry.getKey(), errors);
                    }
                });
            }
        }
        if (value.isArray()) {
            JsonNode items = schema.get("items");
            for (int i = 0; i < value.size(); i++) {
            if (items != null && items.isObject()) {
                    validateNode(value.get(i), items, root, schemas, path + "[" + i + "]", errors);
                }
            }
            checkNumber(value.size(), schema.get("minItems"), schema.get("maxItems"),
                    path + " item count", errors);
        }
        if (value.isTextual()) {
            String text = value.asText();
            checkNumber(text.length(), schema.get("minLength"), schema.get("maxLength"),
                    path + " length", errors);
            JsonNode pattern = schema.get("pattern");
            if (pattern != null && !Pattern.compile(pattern.asText()).matcher(text).find()) {
                errors.add(path + ": 不匹配 pattern " + pattern.asText());
            }
        }
        if (value.isNumber()) {
            checkNumber(value.asDouble(), schema.get("minimum"), schema.get("maximum"),
                    path, errors);
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            errors.add(path + ": 应等于 " + constant);
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            if (enumNode.isEmpty()) {
                errors.add(path + ": enum 不能为空");
            }
            boolean hit = false;
            for (JsonNode candidate : enumNode) {
                hit |= candidate.equals(value);
            }
            if (!hit) {
                errors.add(path + ": 不在 enum 中");
            }
        }
    }

    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String jsonType(JsonNode value) {
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isNull()) return "null";
        return "unknown";
    }

    private static void checkNumber(double value, JsonNode min, JsonNode max,
                                    String label, List<String> errors) {
        if (min != null && min.isNumber() && value < min.asDouble()) {
            errors.add(label + ": 小于 " + min.asDouble());
        }
        if (max != null && max.isNumber() && value > max.asDouble()) {
            errors.add(label + ": 大于 " + max.asDouble());
        }
    }

    /** 将数据契约追加到 Skill instructions，供规划、入账、子任务和终检使用同一模型。 */
    public static String appendTo(String instructions, Iterable<SkillSchema> schemas) {
        if (schemas == null || !schemas.iterator().hasNext()) {
            return instructions;
        }
        StringBuilder sb = new StringBuilder(instructions).append("""

                ## 技能数据结构（自动加载）
                以下 JSON Schema 定义本技能的领域对象、结构化事实与产物形态。基座不理解这些字段；
                使用 `record_facts.payload` 入账领域事实时，必须在 `schema` 中声明对应 `$id` 或
                `$id#/$defs/<定义名>`，并尽量按 Schema 组织数据。Schema 未覆盖的新字段放入 `extensions`，
                不要为了适配 Schema 删除关键信息。
                """);
        for (SkillSchema schema : schemas) {
            sb.append("\n### ").append(schema.id()).append("\n```json\n")
                    .append(schema.content()).append("\n```\n");
        }
        return sb.toString();
    }
}
