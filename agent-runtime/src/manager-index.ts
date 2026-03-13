import { query } from "@anthropic-ai/claude-agent-sdk";
import { MANAGER_SYSTEM_PROMPT } from "./manager-prompt.js";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

const bridgePort = process.env.MANAGER_BRIDGE_PORT!;
const message = process.env.MANAGER_MESSAGE!;
const sessionId = process.env.MANAGER_SESSION_ID!;
const isResume = process.env.MANAGER_IS_RESUME === "true";

if (!bridgePort || !message) {
  console.error("Missing required env vars: MANAGER_BRIDGE_PORT, MANAGER_MESSAGE");
  process.exit(1);
}

if (!sessionId) {
  console.error("Missing required env var: MANAGER_SESSION_ID");
  process.exit(1);
}

const bridgeUrl = `http://localhost:${bridgePort}`;

async function checkIntervention(): Promise<string | null> {
  try {
    const res = await fetch(`${bridgeUrl}/manager/intervention`);
    if (!res.ok) return null;
    const data = await res.json() as { message?: string };
    return data.message || null;
  } catch {
    return null;
  }
}

async function sendLog(type: string, msg: string): Promise<void> {
  try {
    await fetch(`${bridgeUrl}/log`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type, message: msg }),
    });
  } catch {
    // Non-fatal
  }
}

async function main() {
  const distDir = __dirname.endsWith("src")
    ? join(__dirname, "..", "dist")
    : __dirname;
  const mcpServerPath = join(distDir, "manager-mcp-server.js");

  await sendLog("thought", `Manager received: ${message} (session: ${sessionId}, resume: ${isResume})`);

  const prompt = isResume
    ? `[Command]\n${message}`
    : `${MANAGER_SYSTEM_PROMPT}\n\n[Command]\n${message}`;

  const queryOptions: Record<string, unknown> = {
    permissionMode: "bypassPermissions",
    allowDangerouslySkipPermissions: true,
    maxTurns: 50,
    tools: [],
    env: { ...process.env, CLAUDECODE: undefined },
    mcpServers: {
      "manager-bridge": {
        command: "node",
        args: [mcpServerPath],
        env: {
          MANAGER_BRIDGE_URL: bridgeUrl,
        },
      },
    },
  };

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
      // Check for interventions (schedule triggers, player messages)
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
