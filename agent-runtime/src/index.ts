import { query } from "@anthropic-ai/claude-agent-sdk";
import { SYSTEM_PROMPT } from "./prompt.js";
import { checkIntervention, sendLog } from "./intervention.js";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

const bridgePort = process.env.AGENT_BRIDGE_PORT!;
const message = process.env.AGENT_MESSAGE!;
const sessionId = process.env.AGENT_SESSION_ID!;
const isResume = process.env.AGENT_IS_RESUME === "true";
const agentName = process.env.AGENT_NAME || "Agent";

if (!bridgePort || !message) {
  console.error("Missing required env vars: AGENT_BRIDGE_PORT, AGENT_MESSAGE");
  process.exit(1);
}

if (!sessionId) {
  console.error("Missing required env var: AGENT_SESSION_ID");
  process.exit(1);
}

const bridgeUrl = `http://localhost:${bridgePort}`;

async function main() {
  // Determine MCP server script path
  // __dirname is src/ (tsx) or dist/ (node) — compiled .js always in dist/
  const distDir = __dirname.endsWith("src")
    ? join(__dirname, "..", "dist")
    : __dirname;
  const mcpServerPath = join(distDir, "mcp-server.js");

  await sendLog("thought", `Received command: ${message} (session: ${sessionId}, resume: ${isResume})`);

  // First command: full system prompt + sessionId
  // Subsequent commands: resume previous session + just the new command
  const prompt = isResume
    ? `[Command]\n${message}`
    : `${SYSTEM_PROMPT}\n\n[Command]\n${message}`;

  const queryOptions: Record<string, unknown> = {
    permissionMode: "bypassPermissions",
    allowDangerouslySkipPermissions: true,
    maxTurns: 50,
    tools: [],  // no built-in tools, only MCP
    env: { ...process.env, CLAUDECODE: undefined },  // allow nested launch
    mcpServers: {
      "agent-bridge": {
        command: "node",
        args: [mcpServerPath],
        env: {
          AGENT_BRIDGE_URL: bridgeUrl,
          AGENT_NAME: agentName,
        },
      },
    },
  };

  // Session continuity: resume previous conversation or start new with fixed sessionId
  if (isResume) {
    queryOptions.resume = sessionId;
  } else {
    queryOptions.sessionId = sessionId;
  }

  let totalTurns = 0;

  try {
    for await (const msg of query({
      prompt,
      options: queryOptions as Parameters<typeof query>[0]["options"],
    })) {
      // Check for player intervention each turn
      const intervention = await checkIntervention();
      if (intervention) {
        await sendLog("intervention", intervention);
      }

      if (msg.type === "assistant" && msg.message?.content) {
        for (const block of msg.message.content) {
          if ("thinking" in block && block.thinking) {
            await sendLog("thought", block.thinking);
            console.log(JSON.stringify({ type: "thought", text: block.thinking }));
          } else if ("text" in block && block.text) {
            await sendLog("chat", block.text);
            console.log(JSON.stringify({ type: "chat", text: block.text }));
          }
          if ("name" in block && block.name) {
            await sendLog("action", `${block.name}(${JSON.stringify(block.input || {})})`);
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
}

main().catch((e) => {
  console.error("Fatal error:", e);
  process.exit(1);
});
