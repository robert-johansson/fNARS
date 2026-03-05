// Test for the ONA MCP server using the MCP SDK client
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

async function main() {
  const transport = new StdioClientTransport({
    command: "node",
    args: [resolve(__dirname, "index.js")],
  });

  const client = new Client({ name: "test-client", version: "1.0" });
  await client.connect(transport);

  console.log("Connected to ONA MCP server!\n");

  // List available tools
  const tools = await client.listTools();
  console.log(
    "Available tools:",
    tools.tools.map((t) => t.name).join(", ")
  );
  console.log("");

  // 1. Reset ONA
  console.log("--- Reset ---");
  let result = await client.callTool({ name: "reset", arguments: {} });
  console.log(result.content[0].text);
  console.log("");

  // 2. Send a belief
  console.log("--- Send belief: <cat --> animal>. :|: ---");
  result = await client.callTool({
    name: "send_narsese",
    arguments: { input: "<cat --> animal>. :|:" },
  });
  console.log(result.content[0].text);
  console.log("");

  // 3. Send another belief
  console.log("--- Send belief: <dog --> animal>. :|: ---");
  result = await client.callTool({
    name: "send_narsese",
    arguments: { input: "<dog --> animal>. :|:" },
  });
  console.log(result.content[0].text);
  console.log("");

  // 4. Run some cycles
  console.log("--- Run 10 cycles ---");
  result = await client.callTool({
    name: "run_cycles",
    arguments: { n: 10 },
  });
  console.log(result.content[0].text);
  console.log("");

  // 5. Get concepts
  console.log("--- Get concepts ---");
  result = await client.callTool({ name: "get_concepts", arguments: {} });
  console.log(result.content[0].text);
  console.log("");

  // 6. Configure motor babbling
  console.log("--- Configure motor babbling ---");
  result = await client.callTool({
    name: "configure",
    arguments: { command: "*motorbabbling=0.05" },
  });
  console.log(result.content[0].text);
  console.log("");

  // 7. Send a goal
  console.log("--- Send goal: <reward --> good>! :|: ---");
  result = await client.callTool({
    name: "send_narsese",
    arguments: { input: "<reward --> good>! :|:" },
  });
  console.log(result.content[0].text);
  console.log("");

  // 8. Get cycling events
  console.log("--- Get cycling events ---");
  result = await client.callTool({
    name: "get_cycling_events",
    arguments: {},
  });
  console.log(result.content[0].text);
  console.log("");

  // 9. Multi-line Narsese
  console.log("--- Multi-line input ---");
  result = await client.callTool({
    name: "send_narsese",
    arguments: {
      input: `*reset
<green --> seen>. :|:
3
<({SELF}) --> ^left>. :|:
3
<reward --> good>. :|:
3
*concepts`,
    },
  });
  console.log(result.content[0].text);

  await client.close();
  console.log("\nDone!");
  process.exit(0);
}

main().catch((e) => {
  console.error("Error:", e);
  process.exit(1);
});
