# MVP 범위

## 포함

- [ ] Fake Player 소환/제거
- [ ] 기본 행동: 이동, 채굴, 배치, 줍기, 장비
- [ ] pathfinding (A* 또는 기존 라이브러리)
- [ ] 바닐라 GUI 조작: 상자, 화로, 제작대, 주민 거래
- [ ] 엔티티 상호작용: 공격, 주민 유인
- [ ] Observation 시스템: 주변 블록/엔티티/인벤토리 + 작업 요약
- [ ] HTTP Bridge (localhost REST API)
- [ ] Agent Runtime (Claude Agent SDK + tool 정의)
- [ ] 메모리 시스템: 월드별 파일 기반 저장 + 메모리 tool
- [ ] `/agent` 인게임 명령어
- [ ] 모니터링: terminal-mod 탭 연동 (optional) + 채팅 fallback
- [ ] Mod Compat Layer 구조 + AE2/Create 최소 연동

## 미포함 (MVP 이후)

- 멀티 에이전트 (여러 봇 동시 운용)
- 자율 모드 (명령 없이 알아서 행동)
- Stranger Mod 연동 (NPC 지능화)
- 비주얼 피드백 (Agent 시점 화면)
- 플레이어 속성 (체력, 허기, 경험치)

## 기술 스택

| 구성 요소 | 기술 |
|---|---|
| Mod | Forge 1.20.1 (47.2.0), Java 17 |
| Fake Player | `net.minecraftforge.common.util.FakePlayer` |
| Pathfinding | 자체 구현 또는 바리톤 알고리즘 참고 |
| HTTP Server | JDK `com.sun.net.httpserver.HttpServer` (terminal-mod과 동일 패턴) |
| Agent Runtime | Node.js + `@anthropic-ai/claude-agent-sdk` (TypeScript) |
| 통신 | HTTP REST (localhost) |
| 모드 연동 | Forge Optional Dependencies + compat 모듈 |

## 제약 사항

- **Claude API 지연**: tool 호출당 ~1초. 실시간 전투는 부적합. 전투는 로컬 로직으로 처리하고 전략적 판단만 Claude에 위임.
- **서버 사이드 전용**: 클라이언트 렌더링은 하지 않음. Agent는 다른 플레이어에게 스킨 있는 플레이어로 보임.
- **싱글 스레드 주의**: 마크 서버 틱은 싱글 스레드. Action 실행은 틱 단위로 분할 처리 필요.
