package com.hxh.apboa.engine.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述：平台 DB 技能仓库（v2 AgentSkillRepository 实现）
 * <p>
 * 替代 v1 的 {@code SkillBox}（@Deprecated forRemoval）：将平台内置技能与
 * 数据库技能包统一暴露为 v2 技能仓库，由框架的 {@code DynamicSkillMiddleware}
 * 在每次 call() 时自动构建技能提示并注册 {@code load_skill} 工具。
 * <p>
 * 本仓库为只读实现（技能由控制台管理，不通过 agent 写入）。
 *
 * @author huxuehao
 **/
@Slf4j
public class DbAgentSkillRepository implements AgentSkillRepository {

    private static final String SOURCE = "platform-db";

    private final Map<String, AgentSkill> skills = new LinkedHashMap<>();
    private final AgentSkillRepositoryInfo info =
            new AgentSkillRepositoryInfo(SOURCE, SOURCE, false);

    public DbAgentSkillRepository(List<AgentSkill> skillList) {
        if (skillList != null) {
            for (AgentSkill skill : skillList) {
                if (skill != null && skill.getName() != null) {
                    skills.put(skill.getName(), skill);
                }
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    @Override
    public boolean save(List<AgentSkill> agentSkills, boolean overwrite) {
        // 只读仓库：技能由控制台管理
        log.debug("DbAgentSkillRepository is read-only, save ignored ({} skills)", 
                agentSkills == null ? 0 : agentSkills.size());
        return false;
    }

    @Override
    public boolean delete(String name) {
        // 只读仓库
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return skills.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return info;
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读仓库，忽略
    }

    @Override
    public boolean isWriteable() {
        return false;
    }
}
