package com.hxh.apboa.engine.model.impl;

import com.hxh.apboa.common.enums.ModelProviderType;
import com.hxh.apboa.common.wrapper.ModelConfigWrapper;
import com.hxh.apboa.engine.model.IChatModel;
import com.hxh.apboa.engine.model.GenerateOptionsHelper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import io.agentscope.extensions.model.gemini.formatter.GeminiMultiAgentFormatter;
import org.springframework.stereotype.Component;

/**
 * 描述：Gemini 模型
 *
 * @author huxuehao
 **/
@Component
public class DefaultGeminiModelI implements IChatModel {
    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        GeminiChatModel.Builder builder = GeminiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .vertexAI(false)
                .streamEnabled(config.getStreaming() != null && config.getStreaming())
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.isMulti()) {
            builder.formatter(new GeminiMultiAgentFormatter());
        } else {
            builder.formatter(new GeminiChatFormatter());
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        GeminiChatModel.Builder builder = GeminiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .vertexAI(false)
                .streamEnabled(false);

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.GEMINI;
    }

    @Override
    public int order() {
        return 0;
    }
}
