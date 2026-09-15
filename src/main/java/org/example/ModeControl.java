package org.example;

/**
 * 执行模式控制：analyze_query 首轮判定述求适合「编排（主+子）」还是「单代理」，
 * 父代理据此切换工具集。首判后锁定——模式是述求的性质，不随进度或重规划翻转。
 */
public final class ModeControl {

    /** 编排模式（默认）：主+子，检索取数下沉子代理。 */
    public static final String ORCHESTRATOR = "orchestrator";

    /** 单代理模式：禁用 execute_task，父代理亲自检索取数。 */
    public static final String SINGLE = "single";

    private volatile String mode = ORCHESTRATOR;
    private volatile boolean decided = false;

    public String get() {
        return mode;
    }

    /** 归一化并落定；首次收到合法模式后锁定，后续调用忽略（无效值不判定、不锁定）。 */
    public void set(String m) {
        if (decided) {
            return;
        }
        if (ORCHESTRATOR.equals(m)) {
            decided = true;
            mode = ORCHESTRATOR;
        } else if (SINGLE.equals(m)) {
            decided = true;
            mode = SINGLE;
        }
    }
}
