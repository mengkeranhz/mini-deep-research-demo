package org.example;

/**
 * 会话级已加载技能：load_skill 写入，analyze_query 读取——命中技能时把正文
 * 注入规划上下文，任务清单才能按技能的工作流程拆解（规划器的内部调用看不到
 * 主对话，技能正文必须显式带给它）。随 ToolRegistry 构造、一次 Agent 运行存活，
 * 天然随会话重置。
 */
public final class SkillState {

    private Skill active;

    /** 记录当前生效的技能。 */
    public void set(Skill skill) {
        this.active = skill;
    }

    /** 当前生效的技能；本次会话未加载过返回 null。 */
    public Skill get() {
        return active;
    }
}
