# Anthropic SDK 비교 — 어떤 걸 써야 하는가

Anthropic에는 이름이 비슷한 SDK가 있다. 목적과 과금이 완전히 다르므로 혼동 주의.

## 1. Claude Agent SDK (= Claude Code SDK)

> `@anthropic-ai/claude-agent-sdk` (Node.js) / `claude-agent-sdk` (Python)

- **정체**: Claude Code CLI를 **서브프로세스로 실행**하는 래퍼
- **동작 방식**: SDK가 내부적으로 `claude` CLI 프로세스를 spawn → CLI가 LLM 호출 + tool 실행
- **인증**: Claude Code 로그인 상태를 그대로 사용 (별도 API 키 불필요)
- **과금**: Claude Code가 Max 구독이면 **구독 요금 내에서 사용** (API 과금 없음)
- **제공 도구**: Claude Code의 모든 도구 (Read, Write, Edit, Bash, Glob, Grep 등)
- **커스텀 도구**: MCP 서버로 자체 tool 등록 가능
- **용도**: 코드베이스 이해, 파일 편집, 명령 실행, 자율 워크플로우

```typescript
// Node.js
import { query } from "@anthropic-ai/claude-agent-sdk";

for await (const message of query({
  prompt: "철 64개를 창고에서 가져와",
  options: {
    allowedTools: ["Read", "Bash", "my-mcp-tool"],
    permissionMode: "bypassPermissions",
    maxTurns: 10,
  },
})) { /* ... */ }
```

```python
# Python
from claude_agent_sdk import query

async for message in query(prompt="철 64개를 창고에서 가져와"):
    print(message)
```

## 2. Anthropic SDK (Platform API)

> `@anthropic-ai/sdk` (Node.js) / `anthropic` (Python)

- **정체**: Anthropic REST API를 직접 호출하는 **HTTP 클라이언트**
- **동작 방식**: `api.anthropic.com`에 직접 요청 → 응답 수신
- **인증**: **API 키 필수** (`ANTHROPIC_API_KEY`)
- **과금**: **토큰당 종량제** (구독과 완전 별개)
- **제공 도구**: 없음 — tool 정의를 직접 작성해서 보내야 함
- **용도**: 범용 LLM 호출, 챗봇, 텍스트 생성, 커스텀 에이전트

```typescript
// Node.js
import Anthropic from "@anthropic-ai/sdk";
const client = new Anthropic(); // ANTHROPIC_API_KEY 환경변수 필요

const response = await client.messages.create({
  model: "claude-sonnet-4-6",
  max_tokens: 1024,
  messages: [{ role: "user", content: "Hello" }],
  tools: [{ name: "mine_block", description: "...", input_schema: {...} }],
});
```

## 비교 요약

| | Claude Agent SDK | Anthropic SDK (Platform API) |
|---|---|---|
| 패키지 | `@anthropic-ai/claude-agent-sdk` | `@anthropic-ai/sdk` / `anthropic` |
| 내부 동작 | Claude Code CLI 서브프로세스 | REST API 직접 호출 |
| 인증 | Claude Code 로그인 (Max 구독) | API 키 |
| **과금** | **구독 포함 (Max $100/$200)** | **토큰당 종량제** |
| 빌트인 도구 | Read, Write, Bash 등 전체 | 없음 (직접 정의) |
| 커스텀 도구 | MCP 서버로 등록 | tool 스키마 직접 작성 |
| 상태 관리 | 대화 컨텍스트 자동 유지 | 직접 관리 |
| 적합한 용도 | 코딩 에이전트, 파일/시스템 조작 | 범용 LLM 앱, 챗봇 |

## 이 프로젝트의 선택: Claude Agent SDK

- 개인 사용 → Max 구독으로 커버 가능, API 종량제 비용 부담 없음
- 커스텀 tool을 MCP 서버로 등록 → agent-mod HTTP API와 자연스럽게 연결
- Claude Code의 빌트인 도구도 활용 가능 (파일 읽기, 명령 실행 등)
