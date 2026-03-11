const bridgeUrl = process.env.AGENT_BRIDGE_URL || "http://localhost:3000";

export async function checkIntervention(): Promise<string | null> {
  try {
    const res = await fetch(`${bridgeUrl}/intervention`);
    if (!res.ok) return null;
    const data = await res.json() as { message?: string };
    return data.message || null;
  } catch {
    return null;
  }
}

export async function sendLog(type: string, message: string): Promise<void> {
  try {
    await fetch(`${bridgeUrl}/log`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type, message }),
    });
  } catch {
    // Log delivery failure is non-fatal
  }
}
