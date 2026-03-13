# HTTP Bridge (통신)

Mod ↔ Agent Runtime 간 REST API. JDK `com.sun.net.httpserver.HttpServer` 사용.
멀티 에이전트 환경에서 per-agent 라우팅 지원.

## Per-Agent 엔드포인트

```
GET  /agent/{name}/observation    # 해당 에이전트 Observation 조회
POST /agent/{name}/action         # 해당 에이전트 Action 실행
GET  /agent/{name}/status         # 에이전트 상태 (spawned, runtime, position)
GET  /agent/{name}/intervention   # 대기 중인 개입 메시지 수신
POST /agent/{name}/spawn          # 에이전트 소환 (body: name, x, y, z)
POST /agent/{name}/despawn        # 에이전트 제거
GET  /agent/{name}/persona        # PERSONA.md 읽기 (JSON)
POST /agent/{name}/persona        # PERSONA.md 저장 (body: role, personality, tools[])
POST /agent/{name}/memory/create  # 에이전트 스코프 메모리 생성
POST /agent/{name}/memory/search  # 에이전트+글로벌 병합 검색
POST /agent/{name}/memory/get     # 메모리 상세 조회
POST /agent/{name}/memory/update  # 메모리 수정
POST /agent/{name}/memory/delete  # 메모리 삭제
```

## Global 엔드포인트

```
GET  /agents                      # 스폰된 에이전트 목록 (이름, 위치, 런타임 상태)
GET  /agents/list                 # 전체 에이전트 (디렉토리 스캔 + 스폰 상태)
POST /agents/delete               # 에이전트 폴더 삭제 (미스폰 상태만)
GET  /actions                     # 사용 가능한 Action 목록 (모드 compat 포함)
POST /log                         # Agent Runtime → 모니터에 로그 전달
POST /memory/create               # 글로벌 스코프 메모리 생성
POST /memory/get                  # 메모리 상세 조회
POST /memory/update               # 메모리 수정
POST /memory/delete               # 메모리 삭제
POST /memory/search               # 메모리 검색 (scope 파라미터: all/global/agent:name)
```

## 포트 관리

- `localhost:0` (자동 포트 할당)
- 포트 파일: `run/.agent/bridge-server.json` (`{"port": N, "pid": N}`)
- 클라이언트 (`BridgeClient`): 포트 파일 읽어서 캐시, `get()`/`post()` 비동기 호출
