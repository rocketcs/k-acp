package com.hxh.apboa.engine.model.impl;

import com.hxh.apboa.common.enums.ModelProviderType;
import com.hxh.apboa.common.wrapper.ModelConfigWrapper;
import com.hxh.apboa.engine.formatter.FixedSysMsgOpenAIChatFormatter;
import com.hxh.apboa.engine.formatter.FixedSysMsgOpenAIMultiAgentFormatter;
import com.hxh.apboa.engine.model.IChatModel;
import com.hxh.apboa.engine.model.GenerateOptionsHelper;
import com.hxh.apboa.engine.model.HttpTransportHelper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import org.springframework.stereotype.Component;

/**
 * 描述：OpenAI 模型
 *
 * @author huxuehao
 **/
@Component
public class DefaultOpenAIModelI implements IChatModel {
    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                // AgentScope 2.0.2's blocking OkHttp producer batches deltas under ReAct backpressure.
                .httpTransport(HttpTransportHelper.createJdkHttpTransport())
                .generateOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (config.isMulti()) {
            if (config.getFixedSystemMessage() != null && config.getFixedSystemMessage()) {
                builder.formatter(new FixedSysMsgOpenAIMultiAgentFormatter());
            } else {
                builder.formatter(new OpenAIMultiAgentFormatter());
            }

        } else {
            if (config.getFixedSystemMessage() != null && config.getFixedSystemMessage()) {
                builder.formatter(new FixedSysMsgOpenAIChatFormatter());
            } else {
                builder.formatter(new OpenAIChatFormatter());
            }
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .httpTransport(HttpTransportHelper.createOkHttpTransport(10, 15));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.OPEN_AI;
    }

    @Override
    public int order() {
        return 0;
    }
}
