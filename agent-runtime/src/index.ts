import { query } from "@anthropic-ai/claude-agent-sdk";
import { SYSTEM_PROMPT } from "./prompt.js";
import { loadMemory, formatMemoryForPrompt } from "./memory/loader.js";
import { addTaskHistory } from "./memory/tools.js";
import { checkIntervention, sendLog } from "./intervention.js";
import { TaskTracker } from "./task-summary.js";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

const bridgePort = process.env.AGENT_BRIDGE_PORT!;
const message = process.env.AGENT_MESSAGE!;
const worldPath = process.env.AGENT_WORLD_PATH;

if (!bridgePort || !message) {
  console.error("Missing required env vars: AGENT_BRIDGE_PORT, AGENT_MESSAGE");
  process.exit(1);
}

const bridgeUrl = `http://localhost:${bridgePort}`;

async function main() {
  // Load memory if world path available
  let memoryPrompt = "";
  if (worldPath) {
    try {
      const memory = await loadMemory(worldPath);
      memoryPrompt = formatMemoryForPrompt(memory);
    } catch (e) {
      console.error("Failed to load memory:", e);
    }
  }

  const fullPrompt = `${SYSTEM_PROMPT}${memoryPrompt}\n\n[Command]\n${message}`;
  const tracker = new TaskTracker(message);

  // Determine MCP server script path
  // __dirname is src/ (tsx) or dist/ (node) — compiled .js always in dist/
  const distDir = __dirname.endsWith("src")
    ? join(__dirname, "..", "dist")
    : __dirname;
  const mcpServerPath = join(distDir, "mcp-server.js");

  await sendLog("thought", `Received command: ${message}`);

  let totalTurns = 0;

  try {
    for await (const msg of query({
      prompt: fullPrompt,
      options: {
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        maxTurns: 20,
        tools: [],  // no built-in tools, only MCP
        env: { ...process.env, CLAUDECODE: undefined },  // allow nested launch
        mcpServers: {
          "agent-bridge": {
            command: "node",
            args: [mcpServerPath],
            env: {
              AGENT_BRIDGE_URL: bridgeUrl,
              AGENT_WORLD_PATH: worldPath || "",
            },
          },
        },
      },
    })) {
      // Check for player intervention each turn
      const intervention = await checkIntervention();
      if (intervention) {
        await sendLog("intervention", intervention);
      }

      if (msg.type === "assistant" && msg.message?.content) {
        for (const block of msg.message.content) {
          if ("text" in block && block.text) {
            await sendLog("thought", block.text);
            console.log(JSON.stringify({ type: "thought", text: block.text }));
          }
          if ("name" in block && block.name) {
            await sendLog("action", `${block.name}(${JSON.stringify(block.input || {})})`);
            tracker.addAction(block.name, "pending");
            console.log(JSON.stringify({ type: "tool_call", name: block.name, input: block.input }));
          }
        }
      } else if (msg.type === "result") {
        totalTurns = msg.num_turns || 0;
        const resultMsg = `Completed in ${totalTurns} turns, cost: $${msg.total_cost_usd?.toFixed(4) || "?"}`;
        await sendLog("complete", resultMsg);
        console.log(JSON.stringify({
          type: "result",
          turns: totalTurns,
          cost: msg.total_cost_usd,
        }));
      }
    }
  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    const errStack = error instanceof Error ? error.stack : undefined;
    const errFull = JSON.stringify(error, Object.getOwnPropertyNames(error as object));
    await sendLog("error", errMsg);
    console.error("Full error:", errFull);
    console.error("Stack:", errStack);
    console.log(JSON.stringify({ type: "error", message: errMsg }));
  }

  // Save task history
  if (worldPath) {
    try {
      await addTaskHistory(message, `Completed in ${totalTurns} turns`, totalTurns);
    } catch {
      // Non-fatal
    }
  }
}

main().catch((e) => {
  console.error("Fatal error:", e);
  process.exit(1);
});
