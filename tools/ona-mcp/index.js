import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { spawn } from "child_process";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import { z } from "zod";

const __dirname = dirname(fileURLToPath(import.meta.url));
const NAR_PATH = resolve(__dirname, "../../OpenNARS-for-Applications/NAR");

let narProcess = null;
let outputBuffer = "";
let outputResolve = null;

function startNAR() {
  if (narProcess) {
    narProcess.kill();
  }
  narProcess = spawn(NAR_PATH, ["shell"], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  narProcess.stdout.on("data", (data) => {
    outputBuffer += data.toString();
    if (outputResolve) {
      const r = outputResolve;
      outputResolve = null;
      r();
    }
  });
  narProcess.stderr.on("data", (data) => {
    // Ignore stderr
  });
  narProcess.on("close", () => {
    narProcess = null;
  });
}

function sendToNAR(input) {
  return new Promise((resolve, reject) => {
    if (!narProcess) {
      startNAR();
    }
    outputBuffer = "";
    narProcess.stdin.write(input + "\n");
    // Wait for output to settle (NAR processes synchronously per line)
    setTimeout(() => {
      // Give a bit more time for multi-line output
      const check = () => {
        const current = outputBuffer;
        setTimeout(() => {
          if (outputBuffer === current) {
            resolve(outputBuffer.trim());
          } else {
            check();
          }
        }, 50);
      };
      check();
    }, 100);
  });
}

async function sendMultipleLines(lines) {
  const results = [];
  for (const line of lines.split("\n")) {
    const trimmed = line.trim();
    if (trimmed) {
      const result = await sendToNAR(trimmed);
      if (result) results.push(result);
    }
  }
  return results.join("\n");
}

// Create MCP server
const server = new McpServer({
  name: "ona-mcp",
  version: "1.0.0",
});

server.tool(
  "send_narsese",
  "Send one or more lines of Narsese or commands to ONA and return the output",
  { input: z.string().describe("Narsese input or command(s), one per line") },
  async ({ input }) => {
    const result = await sendMultipleLines(input);
    return { content: [{ type: "text", text: result || "(no output)" }] };
  }
);

server.tool(
  "get_concepts",
  "Send *concepts to ONA and return the concept listing",
  {},
  async () => {
    const result = await sendToNAR("*concepts");
    return { content: [{ type: "text", text: result || "(no output)" }] };
  }
);

server.tool(
  "get_cycling_events",
  "Get cycling belief and goal events from ONA",
  {},
  async () => {
    const beliefs = await sendToNAR("*cycling_belief_events");
    const goals = await sendToNAR("*cycling_goal_events");
    return {
      content: [
        {
          type: "text",
          text: `Belief Events:\n${beliefs}\n\nGoal Events:\n${goals}`,
        },
      ],
    };
  }
);

server.tool("reset", "Reset ONA to initial state", {}, async () => {
  startNAR(); // Kill and respawn the NAR process (picks up rebuilt binary)
  await new Promise((r) => setTimeout(r, 200)); // Wait for process to start
  return { content: [{ type: "text", text: "Reset complete (process restarted)" }] };
});

server.tool(
  "run_cycles",
  "Run N inference cycles in ONA",
  { n: z.number().int().positive().describe("Number of cycles to run") },
  async ({ n }) => {
    const result = await sendToNAR(String(n));
    return { content: [{ type: "text", text: result || "(no output)" }] };
  }
);

server.tool(
  "configure",
  "Send a configuration command to ONA (e.g. *motorbabbling=0.9)",
  {
    command: z
      .string()
      .describe("Configuration command like *motorbabbling=0.9"),
  },
  async ({ command }) => {
    const result = await sendToNAR(command);
    return { content: [{ type: "text", text: result || "(configured)" }] };
  }
);

// Start server
startNAR();
const transport = new StdioServerTransport();
await server.connect(transport);
