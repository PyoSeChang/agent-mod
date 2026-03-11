import { readFile } from "fs/promises";
import { existsSync } from "fs";
import { join } from "path";

export interface AgentMemory {
  locations: LocationEntry[];
  facilities: FacilityEntry[];
  preferences: Record<string, string>;
  taskHistory: TaskEntry[];
}

export interface LocationEntry {
  name: string;
  x: number;
  y: number;
  z: number;
  note?: string;
  timestamp?: string;
}

export interface FacilityEntry {
  name: string;
  x: number;
  y: number;
  z: number;
  type: string;
  note?: string;
  timestamp?: string;
}

export interface TaskEntry {
  goal: string;
  result: string;
  timestamp: string;
  turns?: number;
}

export async function loadMemory(worldPath: string): Promise<AgentMemory> {
  const memDir = join(worldPath, ".agent", "agents", "default");

  const memory: AgentMemory = {
    locations: [],
    facilities: [],
    preferences: {},
    taskHistory: [],
  };

  try {
    const locPath = join(memDir, "locations.json");
    if (existsSync(locPath)) {
      memory.locations = JSON.parse(await readFile(locPath, "utf-8"));
    }
  } catch { /* empty or invalid */ }

  try {
    const facPath = join(memDir, "facilities.json");
    if (existsSync(facPath)) {
      memory.facilities = JSON.parse(await readFile(facPath, "utf-8"));
    }
  } catch { /* empty or invalid */ }

  try {
    const prefPath = join(memDir, "preferences.json");
    if (existsSync(prefPath)) {
      memory.preferences = JSON.parse(await readFile(prefPath, "utf-8"));
    }
  } catch { /* empty or invalid */ }

  try {
    const histPath = join(memDir, "task-history.json");
    if (existsSync(histPath)) {
      memory.taskHistory = JSON.parse(await readFile(histPath, "utf-8"));
    }
  } catch { /* empty or invalid */ }

  return memory;
}

export function formatMemoryForPrompt(memory: AgentMemory): string {
  const sections: string[] = [];

  if (memory.locations.length > 0) {
    sections.push("## Known Locations");
    for (const loc of memory.locations) {
      sections.push(`- **${loc.name}**: (${loc.x}, ${loc.y}, ${loc.z})${loc.note ? ` — ${loc.note}` : ""}`);
    }
  }

  if (memory.facilities.length > 0) {
    sections.push("## Known Facilities");
    for (const fac of memory.facilities) {
      sections.push(`- **${fac.name}** [${fac.type}]: (${fac.x}, ${fac.y}, ${fac.z})${fac.note ? ` — ${fac.note}` : ""}`);
    }
  }

  if (Object.keys(memory.preferences).length > 0) {
    sections.push("## Player Preferences");
    for (const [key, value] of Object.entries(memory.preferences)) {
      sections.push(`- ${key}: ${value}`);
    }
  }

  if (memory.taskHistory.length > 0) {
    const recent = memory.taskHistory.slice(-5);
    sections.push("## Recent Tasks");
    for (const task of recent) {
      sections.push(`- ${task.goal} → ${task.result}`);
    }
  }

  if (sections.length === 0) return "";
  return "\n\n[Memory]\n" + sections.join("\n");
}
