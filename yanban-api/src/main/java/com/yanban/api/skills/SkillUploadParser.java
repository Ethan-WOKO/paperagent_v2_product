package com.yanban.api.skills;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class SkillUploadParser {
    public static final int MARKDOWN_BYTES = 64 * 1024;
    public static final int PROMPT_CODE_POINTS = 32_000;
    public static final int METADATA_BYTES = 16 * 1024;
    public static final int REQUEST_BYTES = 512 * 1024;
    public static final List<String> DEFAULT_TOOLS = List.of("list_project_files", "read_project_file", "search_knowledge");
    private static final Set<String> FIELDS = Set.of("name", "description", "allowed_tools", "allowed-tools");

    public record Parsed(String name, String description, String prompt, String metadata, List<String> allowedTools) {}

    public Parsed parse(SkillInstallRequest request) {
        if (request == null) throw invalid("请选择 SKILL.md 文件");
        String markdown = upload(request.markdown(), "SKILL.md", MARKDOWN_BYTES);
        // Runtime receives the full original Markdown, including frontmatter and line endings.
        // JSON Schema maxLength counts Unicode code points, not UTF-16 code units.
        if (markdown.codePointCount(0, markdown.length()) > PROMPT_CODE_POINTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "SKILL.md 超过 32000 个 Unicode 代码点上限（含 YAML 头部和换行），并须同时不超过 64 KiB");
        }
        String metadata = request.metadata() == null ? null : upload(request.metadata(), "skill.yaml", METADATA_BYTES);
        String body = markdown.replace("\r\n", "\n");
        if (body.startsWith("\uFEFF")) body = body.substring(1);
        Map<String, Object> fields = new LinkedHashMap<>();
        if (body.startsWith("---\n")) {
            int end = body.indexOf("\n---\n", 4);
            if (end < 0) throw invalid("SKILL.md 的 YAML 头部需要以单独一行 --- 结束，并包含正文");
            fields.putAll(yaml(body.substring(4, end)));
            body = body.substring(end + 5);
        }
        if (body.isBlank()) throw invalid("SKILL.md 必须包含非空指令正文");
        if (metadata != null) {
            for (var entry : yaml(metadata).entrySet()) {
                if (fields.containsKey(entry.getKey()) && !fields.get(entry.getKey()).equals(entry.getValue())) {
                    throw invalid("SKILL.md 与 skill.yaml 的同名元数据不一致，请只保留一处声明");
                }
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        String name = request.name() == null || request.name().isBlank() ? scalar(fields, "name", 100) : request.name().strip();
        if (name.isBlank()) {
            name = body.lines().filter(line -> line.startsWith("# ")).map(line -> line.substring(2).strip()).findFirst().orElse("");
        }
        if (name.isBlank() || name.length() > 100 || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("请填写 1–100 字的技能名称（或在 YAML name / Markdown 一级标题中提供），不能包含路径或控制字符");
        }
        String description = scalar(fields, "description", 1024);
        if (fields.containsKey("allowed_tools") && fields.containsKey("allowed-tools")) {
            throw invalid("allowed_tools 和 allowed-tools 只能使用一种写法");
        }
        Object tools = fields.getOrDefault("allowed_tools", fields.getOrDefault("allowed-tools", DEFAULT_TOOLS));
        // Common SKILL.md frontmatter uses a whitespace-separated allowed-tools string.
        if (tools instanceof String value) tools = value.isBlank() ? List.of() : List.of(value.trim().split("\\s+"));
        if (!(tools instanceof List<?> values) || values.size() > 32) throw invalid("允许工具必须是最多 32 项的名称列表");
        List<String> allowed = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String tool) || !tool.matches("[a-z][a-z0-9_]{0,63}") || allowed.contains(tool)) {
                throw invalid("工具名称必须唯一，长度为 1–64 个 ASCII 字符，以小写字母开头，后续仅允许小写字母、数字、下划线；不支持大写、点或连字符");
            }
            allowed.add(tool);
        }
        return new Parsed(Normalizer.normalize(name, Normalizer.Form.NFC), description, markdown, metadata, List.copyOf(allowed));
    }

    static String nameKey(String name) { return Normalizer.normalize(name.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }

    private String upload(SkillInstallRequest.Upload file, String filename, int maxBytes) {
        if (file == null || !filename.equals(file.filename())) throw invalid("仅接受文件名为 " + filename + " 的文件，不接受目录、压缩包或脚本");
        String value = file.content();
        if (value == null || value.isBlank()) throw invalid(filename + " 内容不能为空");
        int bytes;
        try { bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining(); }
        catch (CharacterCodingException ex) { throw invalid(filename + " 必须为有效 UTF-8 文本"); }
        if (bytes > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, filename + " 超过 " + (maxBytes / 1024) + " KiB 上限");
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalid(filename + " 包含二进制或不支持的控制字符");
        }
        return value;
    }

    private Map<String, Object> yaml(String text) {
        if (text.length() > METADATA_BYTES) throw invalid("YAML 元数据过大");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(METADATA_BYTES);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (!(loaded instanceof Map<?, ?> map)) throw invalid("YAML 元数据必须为字段映射");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !FIELDS.contains(key) || entry.getValue() == null) {
                    throw invalid("支持的 YAML 字段为 name、description、allowed_tools（或 allowed-tools），不支持其他字段或空值");
                }
                result.put(key, entry.getValue());
            }
            // Validate even when an explicit installation name overrides metadata.
            scalar(result, "name", 100);
            scalar(result, "description", 1024);
            return result;
        } catch (ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) { throw invalid("YAML 格式无效：不支持重复字段、自定义类型、递归引用或过深嵌套"); }
    }

    private String scalar(Map<String, Object> fields, String key, int max) {
        Object value = fields.getOrDefault(key, "");
        if (!(value instanceof String text) || text.length() > max) throw invalid(key + " 必须是不超过 " + max + " 字的文本");
        return text.strip();
    }

    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
