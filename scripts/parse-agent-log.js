#!/usr/bin/env node
/**
 * Pretty-prints agent JSONL log files.
 *
 * Usage:
 *   node scripts/parse-agent-log.js                         # latest log
 *   node scripts/parse-agent-log.js run/.agent/logs/FILE    # specific file
 *   node scripts/parse-agent-log.js --summary               # summary only
 *   node scripts/parse-agent-log.js --failures              # failures only
 */

import { readFileSync, readdirSync } from "fs";
import { join, resolve } from "path";

const args = process.argv.slice(2);
const summaryOnly = args.includes("--summary");
const failuresOnly = args.includes("--failures");
const fileArgs = args.filter((a) => !a.startsWith("--"));

// Find log file
let logPath;
if (fileArgs.length > 0) {
  logPath = resolve(fileArgs[0]);
} else {
  // Find latest in run/.agent/logs/
  const logsDir = resolve("run/.agent/logs");
  try {
    const files = readdirSync(logsDir)
      .filter((f) => f.endsWith(".jsonl"))
      .sort()
      .reverse();
    if (files.length === 0) {
      console.error("No log files found in", logsDir);
      process.exit(1);
    }
    logPath = join(logsDir, files[0]);
  } catch (e) {
    console.error("Cannot read logs directory:", logsDir);
    process.exit(1);
  }
}

console.log(`\n📄 ${logPath}\n${"─".repeat(70)}\n`);

const lines = readFileSync(logPath, "utf-8")
  .split("\n")
  .filter((l) => l.trim());

const entries = lines.map((l) => JSON.parse(l));

// Collect stats
const stats = {
  actions: 0,
  successes: 0,
  failures: 0,
  totalDurationMs: 0,
  actionBreakdown: {},
  substepFailures: [],
};

for (const entry of entries) {
  if (entry.event === "session_start") {
    if (!summaryOnly) {
      console.log(`🟢 Session started`);
      console.log();
    }
    continue;
  }

  if (entry.event === "session_end") {
    if (!summaryOnly) {
      const dur = (entry.duration_ms / 1000).toFixed(1);
      console.log(`\n🔴 Session ended (${dur}s)`);
    }
    continue;
  }

  if (entry.event === "action") {
    stats.actions++;
    stats.totalDurationMs += entry.duration_ms || 0;
    const name = entry.action;
    if (!stats.actionBreakdown[name]) {
      stats.actionBreakdown[name] = { count: 0, ok: 0, fail: 0, totalMs: 0 };
    }
    stats.actionBreakdown[name].count++;
    stats.actionBreakdown[name].totalMs += entry.duration_ms || 0;

    if (entry.ok) {
      stats.successes++;
      stats.actionBreakdown[name].ok++;
    } else {
      stats.failures++;
      stats.actionBreakdown[name].fail++;
    }

    if (!summaryOnly && (!failuresOnly || !entry.ok)) {
      const dur = ((entry.duration_ms || 0) / 1000).toFixed(1);
      const icon = entry.ok ? "✅" : "❌";
      const elapsed = ((entry.session_elapsed_ms || 0) / 1000).toFixed(0);
      console.log(
        `${icon} [${elapsed}s] ${name} (${dur}s)`,
      );

      // Print key params (skip bulky ones)
      const params = entry.params || {};
      const paramKeys = Object.keys(params).filter(
        (k) => !["steps"].includes(k),
      );
      if (paramKeys.length > 0) {
        const paramStr = paramKeys
          .map((k) => {
            const v = params[k];
            if (typeof v === "object") return `${k}=[...]`;
            return `${k}=${v}`;
          })
          .join(", ");
        console.log(`   params: ${paramStr}`);
      }
      if (params.steps) {
        console.log(
          `   steps: ${params.steps.length} actions [${params.steps.map((s) => s.action).join(" → ")}]`,
        );
      }

      // Print result highlights
      const result = entry.result || {};
      if (result.error) console.log(`   error: ${result.error}`);
      if (result.blocks_mined !== undefined)
        console.log(
          `   mined: ${result.blocks_mined}, skipped: ${result.blocks_skipped || 0}`,
        );
      if (result.successes !== undefined)
        console.log(
          `   successes: ${result.successes}, failures: ${result.failures || 0}`,
        );
      if (result.completed_steps !== undefined)
        console.log(
          `   steps: ${result.completed_steps}/${result.total_steps}`,
        );
      if (result.times_crafted !== undefined)
        console.log(
          `   crafted: ${result.times_crafted}x ${result.output_item} (${result.total_output} total)`,
        );
      if (result.mined_block)
        console.log(`   block: ${result.mined_block} (${result.ticks} ticks)`);

      console.log();
    }
    continue;
  }

  if (entry.event === "substep") {
    if (!entry.ok) {
      stats.substepFailures.push(entry);
    }
    if (!summaryOnly && (!failuresOnly || !entry.ok)) {
      const icon = entry.ok ? "  ├" : "  ├❌";
      console.log(
        `${icon} [${entry.parent_action}#${entry.step_index}] ${entry.detail}`,
      );
      if (entry.error) console.log(`  │  error: ${entry.error}`);
    }
    continue;
  }

  // Other events (spawn, despawn, etc.)
  if (!summaryOnly) {
    console.log(`📌 ${entry.event}`, entry.data ? JSON.stringify(entry.data) : "");
  }
}

// Print summary
console.log(`\n${"═".repeat(70)}`);
console.log(`📊 SUMMARY`);
console.log(`${"═".repeat(70)}`);
console.log(
  `Actions: ${stats.actions} (${stats.successes} ok, ${stats.failures} fail)`,
);
console.log(`Total duration: ${(stats.totalDurationMs / 1000).toFixed(1)}s`);
console.log();

console.log("Action breakdown:");
console.log(
  `${"Action".padEnd(25)} ${"Count".padStart(6)} ${"OK".padStart(6)} ${"Fail".padStart(6)} ${"Avg(s)".padStart(8)}`,
);
console.log("─".repeat(55));
for (const [name, data] of Object.entries(stats.actionBreakdown).sort(
  (a, b) => b[1].count - a[1].count,
)) {
  const avg = (data.totalMs / data.count / 1000).toFixed(1);
  console.log(
    `${name.padEnd(25)} ${String(data.count).padStart(6)} ${String(data.ok).padStart(6)} ${String(data.fail).padStart(6)} ${avg.padStart(8)}`,
  );
}

if (stats.substepFailures.length > 0) {
  console.log(`\n⚠️  Substep failures (${stats.substepFailures.length}):`);
  for (const f of stats.substepFailures) {
    console.log(`  [${f.parent_action}#${f.step_index}] ${f.error}: ${f.detail}`);
  }
}

console.log();
