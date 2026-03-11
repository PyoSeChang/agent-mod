import { readFile, writeFile } from "fs/promises";
import { existsSync } from "fs";
import { join } from "path";
import type { LocationEntry, FacilityEntry, TaskEntry } from "./loader.js";

const getMemDir = () => {
  const worldPath = process.env.AGENT_WORLD_PATH;
  if (!worldPath) throw new Error("AGENT_WORLD_PATH not set");
  return join(worldPath, ".agent", "agents", "default");
};

async function readJsonFile<T>(filename: string, defaultValue: T): Promise<T> {
  const path = join(getMemDir(), filename);
  if (!existsSync(path)) return defaultValue;
  try {
    return JSON.parse(await readFile(path, "utf-8"));
  } catch {
    return defaultValue;
  }
}

async function writeJsonFile(filename: string, data: unknown): Promise<void> {
  const path = join(getMemDir(), filename);
  await writeFile(path, JSON.stringify(data, null, 2), "utf-8");
}

export async function rememberLocation(name: string, x: number, y: number, z: number, note?: string): Promise<string> {
  const locations = await readJsonFile<LocationEntry[]>("locations.json", []);
  const existing = locations.findIndex(l => l.name === name);
  const entry: LocationEntry = { name, x, y, z, note, timestamp: new Date().toISOString() };
  if (existing >= 0) {
    locations[existing] = entry;
  } else {
    locations.push(entry);
  }
  await writeJsonFile("locations.json", locations);
  return `Remembered location "${name}" at (${x}, ${y}, ${z})`;
}

export async function rememberFacility(name: string, x: number, y: number, z: number, type: string, note?: string): Promise<string> {
  const facilities = await readJsonFile<FacilityEntry[]>("facilities.json", []);
  const existing = facilities.findIndex(f => f.name === name);
  const entry: FacilityEntry = { name, x, y, z, type, note, timestamp: new Date().toISOString() };
  if (existing >= 0) {
    facilities[existing] = entry;
  } else {
    facilities.push(entry);
  }
  await writeJsonFile("facilities.json", facilities);
  return `Remembered facility "${name}" [${type}] at (${x}, ${y}, ${z})`;
}

export async function rememberPreference(key: string, value: string): Promise<string> {
  const prefs = await readJsonFile<Record<string, string>>("preferences.json", {});
  prefs[key] = value;
  await writeJsonFile("preferences.json", prefs);
  return `Remembered preference: ${key} = ${value}`;
}

export async function recall(query: string): Promise<string> {
  const queryLower = query.toLowerCase();
  const results: string[] = [];

  const locations = await readJsonFile<LocationEntry[]>("locations.json", []);
  for (const loc of locations) {
    if (loc.name.toLowerCase().includes(queryLower) || loc.note?.toLowerCase().includes(queryLower)) {
      results.push(`[Location] ${loc.name}: (${loc.x}, ${loc.y}, ${loc.z})${loc.note ? ` — ${loc.note}` : ""}`);
    }
  }

  const facilities = await readJsonFile<FacilityEntry[]>("facilities.json", []);
  for (const fac of facilities) {
    if (fac.name.toLowerCase().includes(queryLower) || fac.type.toLowerCase().includes(queryLower) || fac.note?.toLowerCase().includes(queryLower)) {
      results.push(`[Facility] ${fac.name} [${fac.type}]: (${fac.x}, ${fac.y}, ${fac.z})${fac.note ? ` — ${fac.note}` : ""}`);
    }
  }

  const prefs = await readJsonFile<Record<string, string>>("preferences.json", {});
  for (const [key, value] of Object.entries(prefs)) {
    if (key.toLowerCase().includes(queryLower) || value.toLowerCase().includes(queryLower)) {
      results.push(`[Preference] ${key}: ${value}`);
    }
  }

  if (results.length === 0) return `No memories found matching "${query}"`;
  return results.join("\n");
}

export async function addTaskHistory(goal: string, result: string, turns?: number): Promise<void> {
  const history = await readJsonFile<TaskEntry[]>("task-history.json", []);
  history.push({ goal, result, timestamp: new Date().toISOString(), turns });
  // Keep only last 50 entries
  if (history.length > 50) history.splice(0, history.length - 50);
  await writeJsonFile("task-history.json", history);
}
