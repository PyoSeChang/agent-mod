export interface TaskSummary {
  goal: string;
  progress: string;
  history: ActionEntry[];
}

interface ActionEntry {
  action: string;
  result: string;
  timestamp: number;
}

export class TaskTracker {
  private goal: string;
  private history: ActionEntry[] = [];

  constructor(goal: string) {
    this.goal = goal;
  }

  addAction(action: string, result: string) {
    this.history.push({
      action,
      result,
      timestamp: Date.now(),
    });
    // Keep last 20 actions
    if (this.history.length > 20) {
      this.history.shift();
    }
  }

  getSummary(): TaskSummary {
    const lastAction = this.history[this.history.length - 1];
    return {
      goal: this.goal,
      progress: lastAction ? `Last: ${lastAction.action} → ${lastAction.result}` : "Starting...",
      history: this.history,
    };
  }

  getProgressText(): string {
    const summary = this.getSummary();
    return `Goal: ${summary.goal}\nActions: ${this.history.length}\n${summary.progress}`;
  }
}
