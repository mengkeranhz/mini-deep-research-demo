package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.tools.AmapClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 工具注册与分发。
 * 注册方式：自动扫描 org.example.tools 包下的全部 AgentTool 实现类并实例化——
 * 新增工具只需在该包内实现 AgentTool 接口，无需修改本类。
 */
public class ToolRegistry {

    /** 自动扫描的工具包。 */
    private static final String TOOL_PACKAGE = "org.example.tools";

    /** 工具接口：名称 + 元信息 + 执行。 */
    public interface AgentTool {
        String name();

        ToolDef definition();

        String execute(JsonNode input) throws Exception;
    }

    /** 工具执行结果；isError=true 时以 is_error 的 tool_result 返回给 LLM（最小容错）。 */
    public record ToolOutput(String content, boolean isError) {}

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Config.Data cfg, TaskStore tasks) {
        AmapClient amap = new AmapClient(cfg.lbs().amapApiKey(), cfg.lbs().minRequestIntervalMs());
        scanPackage(TOOL_PACKAGE).stream()
                .filter(ToolRegistry::isToolClass)
                .sorted(Comparator.comparing(Class::getSimpleName)) // 按类名稳定排序
                .map(c -> instantiate(c, cfg, amap, tasks))
                .forEach(this::register);
    }

    private void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    public List<ToolDef> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    /** 分发执行；未知工具与执行异常都转成 is_error 结果返回。 */
    public ToolOutput run(Block.ToolUse call) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return new ToolOutput("未知工具: " + call.name(), true);
        }
        try {
            return new ToolOutput(tool.execute(call.input()), false);
        } catch (Exception e) {
            return new ToolOutput("工具执行失败: " + e, true);
        }
    }

    // ---- 全量扫描与自动注册 ----

    /** 扫描包内全部类；支持目录（IDE / target/classes）与 jar（打包运行）两种 classpath 形态。 */
    private static List<Class<?>> scanPackage(String pkg) {
        String dir = pkg.replace('.', '/');
        List<Class<?>> classes = new ArrayList<>();
        try {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(dir);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    try (var files = Files.list(Path.of(url.toURI()))) {
                        files.forEach(p -> {
                            String fileName = p.getFileName().toString();
                            if (fileName.endsWith(".class") && !fileName.contains("$")) {
                                classes.add(loadClass(pkg + "." + fileName.substring(0, fileName.length() - 6)));
                            }
                        });
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    try (JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            // 仅本包直接下属的类（跳过内部类与子包）
                            if (name.startsWith(dir + "/") && name.endsWith(".class")
                                    && !name.contains("$")
                                    && name.indexOf('/', dir.length() + 1) < 0) {
                                classes.add(loadClass(name.substring(0, name.length() - 6).replace('/', '.')));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描工具包 " + pkg + " 失败: " + e.getMessage(), e);
        }
        return classes;
    }

    /** 是可实例化的 AgentTool 实现类（排除接口、抽象类与包内非工具类）。 */
    private static boolean isToolClass(Class<?> clazz) {
        return AgentTool.class.isAssignableFrom(clazz)
                && !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    /** 实例化工具：按构造参数类型注入已知依赖（AmapClient / TaskStore / Config 各段），无参构造直接实例化。 */
    private static AgentTool instantiate(Class<?> clazz, Config.Data cfg, AmapClient amap, TaskStore tasks) {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean resolvable = true;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == AmapClient.class) args[i] = amap;
                else if (types[i] == TaskStore.class) args[i] = tasks;
                else if (types[i] == Config.Llm.class) args[i] = cfg.llm();
                else if (types[i] == Config.WebSearch.class) args[i] = cfg.webSearch();
                else if (types[i] == Config.Lbs.class) args[i] = cfg.lbs();
                else if (types[i] == Config.Storage.class) args[i] = cfg.storage();
                else if (types[i] == Config.ReadFile.class) args[i] = cfg.readFile();
                else if (types[i] == Config.Data.class) args[i] = cfg;
                else {
                    resolvable = false;
                    break;
                }
            }
            if (!resolvable) {
                continue;
            }
            try {
                return (AgentTool) ctor.newInstance(args);
            } catch (Exception ignored) {
                // 尝试下一个构造器
            }
        }
        throw new IllegalStateException("无法实例化工具 " + clazz.getName()
                + "：构造参数需为无参或 AmapClient / TaskStore / Config 各段");
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("加载工具类失败: " + name, e);
        }
    }

    // ---- 参数读取辅助 ----

    /** 必填字符串参数。 */
    public static String str(JsonNode input, String key) {
        String v = optStr(input, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return v;
    }

    /** 可选字符串参数，缺省 null。 */
    public static String optStr(JsonNode input, String key) {
        JsonNode v = input.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 可选整数参数，缺省 defaultValue。 */
    public static int optInt(JsonNode input, String key, int defaultValue) {
        JsonNode v = input.get(key);
        return v == null || v.isNull() ? defaultValue : v.asInt(defaultValue);
    }

    /** 必填字符串数组参数。 */
    public static List<String> strList(JsonNode input, String key) {
        JsonNode v = input.get(key);
        if (v == null || !v.isArray()) {
            throw new IllegalArgumentException(key + " 必须是字符串数组");
        }
        List<String> list = new ArrayList<>();
        v.forEach(n -> list.add(n.asText()));
        return list;
    }
}
