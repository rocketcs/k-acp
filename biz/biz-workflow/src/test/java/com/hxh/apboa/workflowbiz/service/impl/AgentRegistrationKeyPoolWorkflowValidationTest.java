package com.hxh.apboa.workflowbiz.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.hxh.apboa.workflowbiz.core.WorkflowDefinitionCompiler;
import com.hxh.apboa.workflowbiz.vo.WorkflowValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistrationKeyPoolWorkflowValidationTest {

    @Test
    void validatesAgentRegistrationKeyPoolWorkflowWithoutExecutingNodes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Object definition = objectMapper.readValue(
                Files.readString(Path.of("..", "..", "docs", "operations", "agent-registration-key-pool-workflow.json")),
                Object.class);
        WorkflowValidatorImpl validator = new WorkflowValidatorImpl(
                objectMapper, new WorkflowDefinitionCompiler(objectMapper));

        WorkflowValidationResult result = validator.validate(definition);

        assertTrue(result.isValid(), () -> "Workflow validation errors: " + result.getErrors());
    }
}
