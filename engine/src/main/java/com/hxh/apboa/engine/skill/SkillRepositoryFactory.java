package com.hxh.apboa.engine.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.consts.SysConst;
import com.hxh.apboa.common.entity.AgentDefinition;
import com.hxh.apboa.common.entity.CodeExecutionConfig;
import com.hxh.apboa.common.entity.SkillFile;
import com.hxh.apboa.common.entity.SkillPackage;
import com.hxh.apboa.common.entity.ToolConfig;
import com.hxh.apboa.common.enums.SkillFileType;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.skill.builtins.UserInteractionProtocolSkill;
import com.hxh.apboa.engine.skill.builtins.VisionEnhancementProtocolSkill;
import com.hxh.apboa.engine.tool.ToolkitFactory;
import com.hxh.apboa.engine.workspace.skills.SearchReplaceSkill;
import com.hxh.apboa.engine.workspace.tool.ConfirmableToolWrapper;
import com.hxh.apboa.engine.workspace.skills.WorkspaceSkill;
import com.hxh.apboa.skill.service.AgentSkillPackageService;
import com.hxh.apboa.skill.service.SkillFileService;
import com.hxh.apboa.skill.service.SkillPackageService;
import com.hxh.apboa.skill.service.SkillToolService;
import com.hxh.apboa.tool.service.ToolService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 描述：skill 仓库构造器（v2 AgentSkillRepository）
 * <p>
 * 替代 v1 的 SkillBoxFactory：将平台内置技能与数据库技能包统一构建为
 * {@link DbAgentSkillRepository}，代码执行工具（Shell/文件读写）直接注册到 Toolkit。
 *
 * @author huxuehao
 **/
@Component
@RequiredArgsConstructor
public class SkillRepositoryFactory {
    private final SkillPackageService skillPackageService;
    private final SkillFileService skillFileService;
    private final AgentSkillPackageService agentSkillPackageService;
    private final SkillToolService skillToolService;
    private final ToolService toolService;

    /**
     * 获取技能仓库
     *
     * @param agentDefinition     智能体定义
     * @param codeExecutionConfig 代码执行配置
     * @return AgentSkillRepository
     */
    public AgentSkillRepository getSkillRepository(AgentDefinition agentDefinition, CodeExecutionConfig codeExecutionConfig) {
        return getSkillRepository(agentDefinition, new Toolkit(), codeExecutionConfig);
    }

    /**
     * 获取技能仓库
     *
     * @param agentDefinition     智能体定义
     * @param toolkit             工具箱（代码执行工具将注册到该工具箱）
     * @param codeExecutionConfig 代码执行配置
     * @return AgentSkillRepository
     */
    public AgentSkillRepository getSkillRepository(AgentDefinition agentDefinition, Toolkit toolkit, CodeExecutionConfig codeExecutionConfig) {
        List<AgentSkill> skills = new ArrayList<>();

        // 用户交互技能
        skills.add(UserInteractionProtocolSkill.getAgentSkill());
        skills.add(VisionEnhancementProtocolSkill.getAgentSkill());

        // 配置代码执行环境（技能 + 工具）
        configureCodeExecution(skills, toolkit, codeExecutionConfig);

        // 注册技能包
        List<Long> skillPackageIds = agentSkillPackageService.getSkillPackageIds(agentDefinition.getId());
        if (!skillPackageIds.isEmpty()) {
            registerSkills(skills, toolkit, skillPackageIds);
        }

        return new DbAgentSkillRepository(skills);
    }

    /**
     * 根据技能包ID列表构建技能仓库
     *
     * @param skillPackageIds 技能包ID列表
     * @param toolkit         工具箱
     * @return AgentSkillRepository
     */
    public AgentSkillRepository getSkillRepository(List<Long> skillPackageIds, Toolkit toolkit) {
        List<AgentSkill> skills = new ArrayList<>();

        // 注册智能体基础技能，不启用代码执行能力。
        skills.add(UserInteractionProtocolSkill.getAgentSkill());
        skills.add(VisionEnhancementProtocolSkill.getAgentSkill());

        if (skillPackageIds != null && !skillPackageIds.isEmpty()) {
            registerSkills(skills, toolkit, skillPackageIds);
        }
        return new DbAgentSkillRepository(skills);
    }

    /**
     * 注册技能包（含工具）到技能列表
     *
     * @param skills          技能列表
     * @param toolkit         工具箱
     * @param skillPackageIds 技能包ID列表
     */
    private void registerSkills(List<AgentSkill> skills, Toolkit toolkit, List<Long> skillPackageIds) {
        List<SkillPackage> skillPackages = skillPackageService.listByIds(skillPackageIds);

        skillPackages.stream()
                .filter(SkillPackage::getEnabled)
                .forEach(skillPackage -> registerSkill(skills, toolkit, skillPackage));
    }

    /**
     * 注册单个技能包
     *
     * @param skills       技能列表
     * @param toolkit      工具箱
     * @param skillPackage 技能包
     */
    private void registerSkill(List<AgentSkill> skills, Toolkit toolkit, SkillPackage skillPackage) {
        // 查询技能包的所有入库文件
        List<SkillFile> files = skillFileService.listBySkillId(skillPackage.getId());
        // 注册技能包中的工具
        List<Long> toolIds = skillToolService.getToolIds(skillPackage.getId());
        List<ToolConfig> toolConfigs = null;
        if (!toolIds.isEmpty()) {
            toolConfigs = toolService.listByIds(toolIds);
            ToolkitFactory.registerTools(toolkit, toolConfigs);
        }

        // 查找 SKILL.md 文件
        String skillContent = files.stream()
                .filter(f -> f.getFileType() == SkillFileType.SKILL_MD)
                .map(SkillFile::getContent)
                .findFirst()
                .orElse("");

        List<SkillFile> resourceFiles = files.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .toList();

        AgentSkill baseSkill = AgentSkill.builder()
                .name(skillPackage.getName())
                .description(skillPackage.getDescription())
                .skillContent((skillContent))
                .build();

        AgentSkill.Builder skillBuilder = baseSkill.toBuilder()
                .skillContent(
                        addToolInfoToContent(
                                appendResourceUsageHint(baseSkill, resourceFiles),
                                toolConfigs))
                .source(SysConst.SKILL_SOURCE);

        // 添加所有资源引用（references/examples/scripts 类型的文件）
        files.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .forEach(f -> skillBuilder.addResource(f.getFilePath(), f.getContent()));

        skills.add(skillBuilder.build());
    }

    /**
     * 配置代码执行环境
     * <p>
     * v2 变更：v1 通过 SkillBox.codeExecution() 配置，v2 直接将工具注册到 Toolkit，
     * 并把工作空间技能加入技能仓库。
     *
     * @param skills 技能列表
     * @param toolkit 工具箱
     * @param config  代码执行配置
     */
    private void configureCodeExecution(List<AgentSkill> skills, Toolkit toolkit, CodeExecutionConfig config) {
        if (toolkit == null || config == null) {
            return;
        }

        // 配置工作空间专属skill
        skills.add(WorkspaceSkill.getAgentSkill());

        // 工作目录
        String workDir = SysConst.getWorkspacePath() + "/" + AgentContext.get().getThreadId();

        // 配置Shell命令工具
        if (Boolean.TRUE.equals(config.getEnableShell())) {
            Set<String> allowedCommands = parseAllowedCommands(config.getCommand());
            toolkit.registerTool(new ConfirmableToolWrapper(new ShellCommandTool(null, allowedCommands, null)));
        }

        // 配置文件读写工具
        if (Boolean.TRUE.equals(config.getEnableRead())) {
            toolkit.registerTool(new ReadFileTool(workDir));
        }
        if (Boolean.TRUE.equals(config.getEnableWrite())) {
            toolkit.registerTool(new WriteFileTool(workDir));
            // 配置工作空间专属skill
            skills.add(SearchReplaceSkill.getAgentSkill());
        }
    }

    /**
     * 解析允许执行的命令集合
     *
     * @param commandJson 命令JSON节点
     * @return 允许执行的命令集合
     */
    private Set<String> parseAllowedCommands(JsonNode commandJson) {
        Set<String> commands = new HashSet<>();
        if (commandJson == null || commandJson.isEmpty()) {
            return commands;
        }
        if (commandJson.isArray()) {
            commandJson.forEach(node -> commands.add(node.asText()));
        }
        return commands;
    }

    /**
     * 添加资源使用提示
     *
     * @param baseSkill     基础技能
     * @param resourceFiles 资源文件列表
     * @return 添加资源使用提示后的技能内容
     */
    private String appendResourceUsageHint(AgentSkill baseSkill, List<SkillFile> resourceFiles) {
        if (resourceFiles == null || resourceFiles.isEmpty()) {
            return baseSkill.getSkillContent();
        }

        List<String> resourcePaths = resourceFiles.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .map(SkillFile::getFilePath)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.replace('\\', '/'))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (resourcePaths.isEmpty()) {
            return baseSkill.getSkillContent();
        }

        String skillContent = baseSkill.getSkillContent();
        String resourceList = resourcePaths.stream()
                .map(path -> "- `" + path + "`")
                .collect(Collectors.joining("\n"));

        String hint = """
            
            ================ Skill Resources Explanation ==================
            
            When this skill refers to files in this directory, examples/, references/, or scripts/,
            treat them as skill resources, not workspace files. Load them with:
            
            `load_skill_through_path(skillId="%s", path="<resource-path>")`
            
            Available resource paths:
            %s""".formatted(baseSkill.getSkillId(), resourceList);

        return (skillContent == null ? "" : skillContent) + hint;
    }

    /**
     * 添加工具信息到技能内容
     *
     * @param content     技能内容
     * @param toolConfigs 工具配置列表
     * @return 添加工具信息后的技能内容
     */
    private String addToolInfoToContent(String content, List<ToolConfig> toolConfigs) {
        if (toolConfigs == null || toolConfigs.isEmpty()) {
            return content;
        }

        String toolInfo = toolConfigs.stream()
                .map(tool -> "toolName：" + tool.getToolId() + "\ntoolDesc: " + tool.getDescription())
                .collect(Collectors.joining("\n---\n"));

        return content + "\n\n================ Available Tools (You can use the following tools) ==============\n" + toolInfo;
    }
}
