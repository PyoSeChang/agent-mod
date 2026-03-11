// Tests Claude Agent SDK query function
// Does NOT require Minecraft - tests SDK connectivity only

import { query } from "@anthropic-ai/claude-agent-sdk";

async function main() {
  console.log("Testing Claude Agent SDK...\n");

  // Remove CLAUDECODE to allow nested launch
  const env = { ...process.env };
  delete env.CLAUDECODE;

  try {
    let gotResult = false;
    for await (const msg of query({
      prompt: "Reply with exactly: AGENT_SDK_OK",
      options: {
        maxTurns: 1,
        tools: [],
        env,
      },
    })) {
      if (msg.type === "assistant" && msg.message?.content) {
        for (const block of msg.message.content) {
          if ("text" in block) {
            console.log("\u2713 Assistant response:", block.text.slice(0, 100));
          }
        }
      } else if (msg.type === "result") {
        gotResult = true;
        console.log(`\u2713 Result: turns=${msg.num_turns}, cost=$${msg.total_cost_usd?.toFixed(4)}`);
      }
    }

    if (gotResult) {
      console.log("\n\u2713 Agent SDK test PASSED");
    } else {
      console.log("\n\u2717 Agent SDK test FAILED - no result received");
      process.exit(1);
    }
  } catch (e) {
    console.error("\u2717 Agent SDK test FAILED:", e instanceof Error ? e.message : e);
    process.exit(1);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
