package com.hxh.apboa.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxh.apboa.agent.mapper.AgentDiyPageConfigMapper;
import com.hxh.apboa.common.entity.AgentDiyPageConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDiyPageConfigServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishCopiesDraftToPublishedConfigAndEnablesPage() throws Exception {
        AgentDiyPageConfigMapper mapper = mock(AgentDiyPageConfigMapper.class);
        AgentDiyPageConfigServiceImpl service = new AgentDiyPageConfigServiceImpl(mapper);
        JsonNode draft = objectMapper.readTree("{\"headline\":\"有什么我能帮你的吗？\"}");
        AgentDiyPageConfig config = new AgentDiyPageConfig();
        config.setAgentDefinitionId(42L);
        config.setDraftConfig(draft);
        config.setEnabled(false);

        when(mapper.selectOne(any())).thenReturn(config);
        when(mapper.updateById(config)).thenReturn(1);

        assertTrue(service.publish(42L));
        assertEquals(draft, config.getPublishedConfig());
        assertNotNull(config.getPublishedAt());
        assertTrue(config.getEnabled());
        verify(mapper).updateById(config);
    }
}
