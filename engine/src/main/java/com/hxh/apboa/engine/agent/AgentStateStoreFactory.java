package com.hxh.apboa.engine.agent;

import com.hxh.apboa.common.util.FuncUtils;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 描述：AgentStateStore 工厂（v2 统一状态存储）
 * <p>
 * 替代 v1 的 {@code Session}/{@code MysqlSession} 体系：
 * AgentScope 2.0 移除了 Session 接口，统一为 {@link AgentStateStore} 抽象，
 * 按 (userId, sessionId) 分区，在每次 call() 时自动 save/load。
 * <p>
 * 数据源可用时使用 MySQL 持久化（支持 HITL 暂停态跨实例恢复），
 * 否则降级为内存存储。
 *
 * @author huxuehao
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStateStoreFactory {

    private final ObjectProvider<DataSource> dataSourceProvider;

    /** 缓存单例：agent 每次请求构建，state store 必须全局共享才能跨请求恢复状态 */
    private volatile AgentStateStore store;

    /**
     * 获取全局共享的 AgentStateStore
     */
    public AgentStateStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    store = createStore();
                }
            }
        }
        return store;
    }

    private AgentStateStore createStore() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.warn("未找到 DataSource，AgentStateStore 降级为内存存储（重启丢失会话/HITL 暂停态）");
            return new InMemoryAgentStateStore();
        }
        try {
            // 第二参数 true：数据库不存在时自动创建（默认库名 agentscope）
            MysqlAgentStateStore mysqlStore = new MysqlAgentStateStore(dataSource, true);
            log.info("AgentStateStore 使用 MySQL 持久化");
            return mysqlStore;
        } catch (Exception e) {
            log.error("初始化 MysqlAgentStateStore 失败，降级为内存存储: {}", e.getMessage(), e);
            return new InMemoryAgentStateStore();
        }
    }

    /**
     * 是否为持久化存储
     */
    public boolean isPersistent() {
        return FuncUtils.isNotEmpty(System.getProperty("spring.datasource.url"))
                || dataSourceProvider.getIfAvailable() != null;
    }
}
