# Monitor (모니터링 + 개입)

Agent의 사고/행동 과정을 모니터링하고 플레이어가 개입할 수 있는 기능. agent-mod 내부 패키지 (`monitor/`).

## terminal-mod 연동 (optional dependency)

agent-mod는 terminal-mod에 하드 의존하지 않는다. Forge의 optional dependency 패턴 사용:

```java
if (ModList.get().isLoaded("terminal")) {
    TerminalIntegration.openMonitorTab();  // ANSI 컬러, 스크롤, 키보드 개입
} else {
    player.sendSystemMessage(Component.literal("[Agent] 채굴 중... (3/64)"));
}
```

- **terminal-mod 있을 때**: 전용 터미널 탭에 리치 로그 (ANSI 컬러, 사고/행동 구분, 스크롤, 키보드 개입)
- **terminal-mod 없을 때**: 인게임 채팅에 요약 로그 + `/agent` 명령어로 개입

## 로그 타입

```
[사고]  - Agent의 판단/추론 과정
[행동]  - tool 호출 및 실행 결과
[관찰]  - Observation 요약
[개입]  - 플레이어의 개입 메시지
[오류]  - 실패/예외
[완료]  - 작업 종료 + 결과 요약
```

## 개입 (Intervention)

| 방법 | 효과 |
|---|---|
| 자연어 입력 (터미널 탭) | Agent의 다음 턴 컨텍스트에 주입 |
| `/agent stop` | 현재 작업 중단 |
| `/agent pause` | 일시 정지 |
| `/agent resume` | 재개 |
