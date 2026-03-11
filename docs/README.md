# Agent Mod — 자동화의 빈틈을 메우는 자연어 로봇

레드스톤, Create, 주민 등으로 자동화할 수 없는 영역을 자연어 명령으로 대신 수행하는 AI 에이전트.

## 설계 원칙

1. **자동화를 대체하지 않는다** — 기존 자동화를 보조. 반복 작업은 자동화 시스템, Agent는 일회성/비정형 작업.
2. **모드팩 호환이 존재 이유** — 바닐라만 지원하면 의미 없음. 모드 기계를 조작할 수 있어야 컨셉 성립.
3. **완벽하지 않아도 된다** — 80%는 알아서, 20%는 물어보는 게 자연스럽다.

## 문서 구조

```
docs/agent-mod/
├── README.md                 # 이 파일 (개요 + 설계 원칙)
├── concept.md                # 왜 필요한가, 시나리오 예시
├── architecture.md           # 전체 아키텍처, 모듈 구성, 사용 흐름
├── components/
│   ├── fake-player.md        # Fake Player 설계
│   ├── action-system.md      # Action 목록 + 모드 행동
│   ├── observation-system.md # Observation 스키마
│   ├── mod-compat.md         # 모드 호환 레이어 (AE2, Create, Mekanism)
│   ├── http-bridge.md        # HTTP API 스펙
│   └── monitor.md            # 모니터링 + 개입 + terminal-mod 연동
├── memory.md                 # 세션 내/외 일관성, 메모리 시스템
├── sdk-comparison.md         # Claude Agent SDK vs Anthropic SDK 비교
├── mvp.md                    # MVP 범위, 기술 스택, 제약 사항
└── agent-runtime.md          # Agent Runtime (Node.js) 설계
```
