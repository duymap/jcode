package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Interface for agent tools that can be called by the LLM.
 */
public interface Tool {

    /** Tool name used in function calling. */
    String name();

    /** Human-readable description for the LLM. */
    String description();

    /**
     * JSON schema for the tool parameters, suitable for OpenAI function calling.
     * Returns a map that will be serialized as the "parameters" field.
     */
    Map<String, Object> parameterSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param args parsed JSON arguments from the LLM
     * @param cwd  current working directory
     * @return tool result as a string
     */
    String execute(JsonNode args, String cwd) throws Exception;
}
