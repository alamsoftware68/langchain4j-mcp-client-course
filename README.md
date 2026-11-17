# langchain4j-mcp-client-course

A Java host application that uses **LangChain4j** to connect an LLM (OpenAI `gpt-4o-mini`) to a locally running custom **MCP (Model Context Protocol) server** over stdio, so the LLM can answer questions about course **listings, prices, and durations** using real tool calls instead of guessing.

This repository contains the **host application + MCP client** side only. The companion MCP server lives in a separate repository: **[langchain4j-custom-mcp-server-course](../langchain4j-custom-mcp-server-course)** (see [How this talks to the MCP server](#how-this-talks-to-the-mcp-server) below).

## What this project demonstrates

A minimal, four-actor MCP setup:

```
Host Application  →  MCP Client  →  MCP Server  →  Tools (business logic)
        ↑                                              │
        └──────────────── LLM (OpenAI) ────────────────┘
```

- **Host application** (`Main.java`) — the process you run. It wires everything together: the LLM, the MCP client/transport, and the AI service.
- **MCP Client** (`langchain4j-mcp`'s `DefaultMcpClient` + `StdioMcpTransport`) — speaks the MCP protocol to the server, discovers its tools, and forwards tool-call requests/responses.
- **LLM** (`OpenAiChatModel`, model `gpt-4o-mini`) — decides *when* a tool needs to be called and turns raw tool results into a natural-language answer.
- **MCP Server** — a separate Java process (built from the `langchain4j-custom-mcp-server-course` repo) exposing three tools: `listCourses`, `getCoursePrice`, `getCourseDuration`.

## Project structure

```
langchain4j-mcp-client-course/
├── pom.xml                                   # Maven build, LangChain4j + MCP dependencies
└── src/main/java/com/org/mcp/
    ├── Main.java                             # Host application entry point (wires LLM + MCP client + AiService)
    └── CourseAssistant.java                  # LangChain4j AiServices interface (the LLM-facing contract)
```

### `pom.xml`
- Java 21, Maven.
- Key dependencies:
  - `langchain4j` — core AI service / tool-calling abstractions.
  - `langchain4j-open-ai` — OpenAI chat model integration.
  - `langchain4j-mcp` — LangChain4j's MCP client integration (transport + tool provider).
- `exec-maven-plugin` is configured so the app can be run directly via Maven, with `com.org.mcp.Main` as the main class.

### `CourseAssistant.java`
A plain interface annotated with LangChain4j's declarative AI-service annotations:

```java
public interface CourseAssistant {
    @SystemMessage("""
        You are a helpful course assistant.
        Use the available MCP tools to answer questions about course prices and durations.
        Do not invent course information.
        """)
    @UserMessage("{{it}}")
    String chat(String message);
}
```

LangChain4j generates a runtime proxy for this interface via `AiServices.builder(...)`. The `@SystemMessage` explicitly instructs the LLM to rely on MCP tools rather than hallucinating course data — this is the behavioral contract between the LLM and the MCP tool layer.

### `Main.java` — the host application
This is the orchestrator. Step by step:

1. **Build the LLM** — `OpenAiChatModel` configured with `gpt-4o-mini`, API key read from the `OPENAI_API_KEY` environment variable.
2. **Create the transport** — `StdioMcpTransport` launches the MCP server as a **child process** (`java -jar custom-mcp-server-1.0-SNAPSHOT-shaded.jar`) and communicates with it over **stdin/stdout** using the MCP JSON-RPC protocol. `logEvents(true)` logs the raw protocol traffic for debugging.
3. **Create the MCP client** — `DefaultMcpClient` wraps the transport under a logical key (`"course-mcp-server"`). This client handles the MCP handshake and exposes operations like "list tools" / "call tool" to the rest of the app.
4. **Create the tool provider** — `McpToolProvider` asks the MCP client for its available tools (`listCourses`, `getCoursePrice`, `getCourseDuration`) and converts their MCP tool schemas into LangChain4j `ToolSpecification`s that the LLM can understand and invoke.
5. **Build the AI service** — `AiServices.builder(CourseAssistant.class)` binds the chat model and the tool provider into a working implementation of `CourseAssistant`.
6. **Ask a question** — `assistant.chat("what are the prices and duration of the courses")` triggers the full request/response flow described below.

## End-to-end flow (Host → Client → LLM → Server → back)

1. The **host application** starts and asks `CourseAssistant.chat(...)` a question.
2. LangChain4j sends the user's message **plus the MCP tool definitions** (fetched via `McpToolProvider`) to the **LLM** (OpenAI).
3. The LLM decides it needs course data it doesn't have, and responds with one or more **tool-call requests** (e.g., "call `getCoursePrice` with `courseName=Java`") instead of a final answer — this is OpenAI's native function/tool-calling mechanism.
4. LangChain4j intercepts these tool-call requests and routes them through the **MCP client** (`DefaultMcpClient`).
5. The MCP client sends a JSON-RPC `tools/call` request over the **stdio transport** to the **MCP server process**.
6. The **MCP server** (running as a separate `java -jar` process) matches the tool name to its registered handler, executes the real Java logic (`CourseServices`), and returns the result as an MCP `CallToolResult`.
7. The result travels back over stdio to the MCP client, which hands it back to LangChain4j.
8. LangChain4j appends the tool result to the conversation and sends it back to the **LLM**, which now has real data to work with.
9. The LLM produces the final natural-language answer, which LangChain4j returns from `assistant.chat(...)`.
10. The **host application** prints the answer to the console.

```
User question
   │
   ▼
Host App (Main.java) ── assistant.chat(msg) ──► LangChain4j AiServices (CourseAssistant proxy)
                                                        │
                                   tool definitions ────┤
                                                        ▼
                                                     OpenAI LLM (gpt-4o-mini)
                                                        │
                                        "call getCoursePrice(Java)"
                                                        ▼
                                            McpToolProvider / DefaultMcpClient
                                                        │  JSON-RPC over stdio
                                                        ▼
                                        MCP Server process (custom-mcp-server.jar)
                                                        │
                                                   CourseServices logic
                                                        │
                                              tool result (text)
                                                        ▼
                                            back to MCP client → LangChain4j → LLM
                                                        │
                                              final natural-language answer
                                                        ▼
                                                 Host App prints answer
```

## How this talks to the MCP server

This client does **not** import any server code — the two processes are completely decoupled and only communicate over **stdio using the MCP protocol**:

- The client launches the server as a subprocess using a **hardcoded path to the shaded (fat) jar**:
  `D:\Coding\Java\custom-mcp-server\target\custom-mcp-server-1.0-SNAPSHOT-shaded.jar`
- The server must therefore be **built first**, in its own repository ([langchain4j-custom-mcp-server-course](../langchain4j-custom-mcp-server-course)), producing that shaded jar via `mvn clean package`.
- Communication uses the **stdio transport**: the client writes MCP JSON-RPC requests to the server's stdin and reads responses from its stdout. There is no network port, no REST call — it's purely a parent/child process pipe, which is why both projects must exist on the same machine.
- The contract between the two sides is the **MCP tool schema** (tool name + JSON input schema) advertised by the server and consumed by `McpToolProvider` on the client. As long as the server exposes `listCourses`, `getCoursePrice`, and `getCourseDuration` with those schemas, the client works regardless of how the server implements them internally.

## Prerequisites

- Java 21+
- Maven 3.8+
- An OpenAI API key exported as `OPENAI_API_KEY`
- The `langchain4j-custom-mcp-server-course` project built into a shaded jar at the path referenced above (adjust the path in `Main.java` if your checkout differs)

## Build & run

```bash
# 1. Build the MCP server first (in the other repo/checkout)
cd ../langchain4j-custom-mcp-server-course
mvn clean package

# 2. Set your OpenAI API key
set OPENAI_API_KEY=sk-...        # PowerShell: $env:OPENAI_API_KEY="sk-..."

# 3. Build and run this client
cd ../langchain4j-mcp-client-course
mvn clean compile exec:java
```

Expected console output includes the MCP handshake/tool-call logs (from `logEvents(true)`) followed by the LLM's final answer summarizing course prices and durations.

## Related repository

- **MCP Server**: [`langchain4j-custom-mcp-server-course`](../langchain4j-custom-mcp-server-course) — implements the three MCP tools (`listCourses`, `getCoursePrice`, `getCourseDuration`) backed by `CourseServices`. See that repository's README for its internal structure and how it exposes tools over stdio.
