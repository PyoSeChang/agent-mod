# Agent Mod — 자동화의 빈틈을 메우는 자연어 로봇

레드스톤, Create, 주민 등으로 자동화할 수 없는 영역을 자연어 명령으로 대신 수행하는 AI 에이전트.

## 설계 원칙

1. **Player-like** — God Mode가 아니라 실제 플레이어처럼 행동한다. 걷고, 도구로 캐고, 호미로 밭을 간다.
2. **자동화를 대체하지 않는다** — 기존 자동화를 보조. 반복 작업은 자동화 시스템, Agent는 일회성/비정형 작업.
3. **모드팩 호환이 존재 이유** — 모든 모드는 플레이어 입력을 전제로 설계됨. Player-like여야 모드 호환이 자연스럽다.
4. **완벽하지 않아도 된다** — 80%는 알아서, 20%는 물어보는 게 자연스럽다.

## 4계층 아키텍처

```
Layer 4: Skills      절차적 지식 (레시피, 모드 사용법)
Layer 3: Memory      월드 상태 (좌표, 시설, 창고, 이력)
Layer 2: MC 로직     pathfinding, 틱 단위 채굴/모션 (Java)
Layer 1: Atomic MCP  useItemOn, attack, interact... (최소 단위)
```

- Skill은 "어떻게"만 알고, "어디서/뭘로"는 Memory에 질의
- Memory는 Skill과 분리된 독립 레이어
- Agent는 "뭘 할지" 판단, "어떻게"는 Minecraft 로직이 처리

## 문서 구조

```
docs/
├── README.md                 # 이 파일
├── concept.md                # 왜 필요한가, 시나리오 예시
├── architecture.md           # 4계층 아키텍처, 시스템 구조
├── components/
│   ├── fake-player.md        # Fake Player 설계
│   ├── action-system.md      # Atomic Action + Player-like 행동
│   ├── observation-system.md # Observation 스키마
│   ├── mod-compat.md         # 모드 호환 레이어
│   ├── http-bridge.md        # HTTP API 스펙
│   └── monitor.md            # 모니터링 + 개입
├── memory.md                 # Session/Persistent 메모리, 층위 구조
├── sdk-comparison.md         # Claude Agent SDK vs Anthropic SDK 비교
├── mvp.md                    # MVP 범위, 기술 스택
├── agent-runtime.md          # Agent Runtime (Node.js) 설계
└── dev-plan.md               # Phase별 개발 계획
```
