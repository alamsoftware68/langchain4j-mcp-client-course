package com.org.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        //adding code for chatmodel and getting api key
        System.out.println("MCP Client / host app Demo");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        //calling custom mcp-server which is available in our local system as jar
        McpTransport transport = StdioMcpTransport.builder()
                .command(List.of(
                        "java",
                        "-jar",
                        "D:\\Coding\\Java\\custom-mcp-server\\target\\custom-mcp-server-1.0-SNAPSHOT-shaded.jar"
                ))
                .logEvents(true)
                .build();

        //Creating MCP-Client
        McpClient mcpClient = DefaultMcpClient.builder()
                .key("course-mcp-server")   //our own mcp client server key, it can be anything
                .transport(transport)
                .build();

        //Getting tools information available on MCP server through mcp-client
        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();

        //AiService
        CourseAssistant assistant = AiServices.builder(CourseAssistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();

        String answer=assistant.chat("what are the prices and duration of the courses");
        System.out.println(answer);
    }
}