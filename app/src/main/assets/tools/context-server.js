#!/usr/bin/env node
const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { CallToolRequestSchema, ListToolsRequestSchema } = require("@modelcontextprotocol/sdk/types.js");

const BRIDGE_PORT = process.env.AGENTICDROID_BRIDGE_PORT || 41337;
const BRIDGE_URL = `http://127.0.0.1:${BRIDGE_PORT}`;

const server = new Server(
  {
    name: "agenticdroid-context",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

async function fetchFromApp(endpoint, method = "GET", body = null) {
  try {
    const response = await fetch(`${BRIDGE_URL}${endpoint}`, {
      method,
      headers: body ? { "Content-Type": "application/json" } : {},
      body: body ? JSON.stringify(body) : null,
    });
    if (!response.ok) {
      throw new Error(`App bridge error: ${response.status} ${response.statusText}`);
    }
    return await response.json();
  } catch (error) {
    throw new Error(`Failed to connect to AgenticDroid app bridge: ${error.message}`);
  }
}

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "get_active_tab",
        description: "Returns the path and content of the currently focused editor tab in AgenticDroid.",
        inputSchema: { type: "object", properties: {} },
      },
      {
        name: "list_open_tabs",
        description: "Lists all files currently open in the AgenticDroid editor.",
        inputSchema: { type: "object", properties: {} },
      },
      {
        name: "save_active_tab",
        description: "Triggers a save of the currently focused tab in the AgenticDroid editor.",
        inputSchema: { type: "object", properties: {} },
      }
    ],
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    switch (request.params.name) {
      case "get_active_tab": {
        const data = await fetchFromApp("/active-tab");
        return {
          content: [{ type: "text", text: JSON.stringify(data, null, 2) }],
        };
      }
      case "list_open_tabs": {
        const data = await fetchFromApp("/tabs");
        return {
          content: [{ type: "text", text: JSON.stringify(data, null, 2) }],
        };
      }
      case "save_active_tab": {
        const data = await fetchFromApp("/save", "POST");
        return {
          content: [{ type: "text", text: `Save status: ${data.status}` }],
        };
      }
      default:
        throw new Error(`Unknown tool: ${request.params.name}`);
    }
  } catch (error) {
    return {
      content: [{ type: "text", text: `Error: ${error.message}` }],
      isError: true,
    };
  }
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("AgenticDroid Context MCP server running...");
}

main().catch((error) => {
  console.error("Server error:", error);
  process.exit(1);
});
