# Agent Runtime (Node.js)

Claude Agent SDK를 사용하여 자연어를 이해하고 agent-mod의 HTTP API를 호출하는 외부 프로세스.

## 구조

```
agent-runtime/              # 모노레포 루트의 별도 디렉터리
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts            # 진입점
│   ├── mcp-server.ts       # agent-mod Action을 MCP tool로 노출
│   └── memory/
│       ├── loader.ts       # 세션 시작 시 메모리 로드
│       └── tools.ts        # remember_*, recall tool 정의
└── memory/                 # 런타임이 아닌 agent-mod가 관리하는 경로 참조
```

## 동작 방식

```typescript
import { query } from "@anthropic-ai/claude-agent-sdk";

// 1. 메모리 로드
const memory = await loadMemory(worldPath, agentId);

// 2. Agent SDK 호출
for await (const message of query({
  prompt: `
    [기억]
    ${JSON.stringify(memory)}

    [명령]
    ${playerCommand}
  `,
  options: {
    allowedTools: ["Read", "Glob", "Grep", ...mcpTools],
    permissionMode: "bypassPermissions",
    maxTurns: 20,
  },
})) {
  // 3. 로그를 agent-mod monitor로 스트리밍
  if (message.type === "assistant") {
    await fetch(`http://localhost:${port}/log`, {
      method: "POST",
      body: JSON.stringify({ type: "thought", content: message }),
    });
  }
}
```

## MCP 서버

agent-mod의 HTTP API를 Claude Agent SDK의 MCP tool로 노출:

```typescript
// agent-mod HTTP API → MCP tool 매핑
const tools = [
  { name: "move_to",         endpoint: "POST /action", params: {action: "move_to", ...} },
  { name: "mine_block",      endpoint: "POST /action", params: {action: "mine_block", ...} },
  { name: "get_observation",  endpoint: "GET /observation" },
  { name: "remember_location", endpoint: "local", handler: memoryTools.rememberLocation },
  { name: "recall",           endpoint: "local", handler: memoryTools.recall },
  // ...
];
```

## 과금

`@anthropic-ai/claude-agent-sdk`는 Claude Code CLI를 서브프로세스로 실행. Max 구독이면 구독 요금 내에서 사용 가능. 상세 비교는 [sdk-comparison.md](./sdk-comparison.md) 참조.
