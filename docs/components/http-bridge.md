# HTTP Bridge (통신)

Mod ↔ Agent Runtime 간 REST API. JDK `com.sun.net.httpserver.HttpServer` 사용 (terminal-mod과 동일 패턴).

## 엔드포인트

```
POST /action          # Action 실행 요청
GET  /observation     # 현재 Observation 조회
POST /spawn           # Agent 소환
POST /despawn         # Agent 제거
GET  /status          # Agent 상태 (alive, position, current_action)
GET  /actions         # 사용 가능한 Action 목록 (모드 compat 포함)
POST /log             # Agent Runtime → 모니터에 로그 전달
GET  /intervention    # 대기 중인 플레이어 개입 메시지 수신
```
