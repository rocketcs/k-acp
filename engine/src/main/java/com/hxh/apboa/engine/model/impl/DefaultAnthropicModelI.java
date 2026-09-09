package com.hxh.apboa.engine.model.impl;

import com.hxh.apboa.common.enums.ModelProviderType;
import com.hxh.apboa.common.wrapper.ModelConfigWrapper;
import com.hxh.apboa.engine.model.IChatModel;
import com.hxh.apboa.engine.model.GenerateOptionsHelper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicMultiAgentFormatter;
import org.springframework.stereotype.Component;

/**
 * 描述：Anthropic 模型
 *
 * @author huxuehao
 **/
@Component
public class DefaultAnthropicModelI implements IChatModel {
    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (config.isMulti()) {
            builder.formatter(new AnthropicMultiAgentFormatter());
        } else {
            builder.formatter(new AnthropicChatFormatter());
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(false);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.ANTHROPIC;
    }

    @Override
    public int order() {
        return 0;
    }
}
