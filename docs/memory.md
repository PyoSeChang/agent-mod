# 메모리 시스템 — 세션 내/외 일관성

## 세션 내 일관성

한 명령 수행 중 (`/agent 철 64개 캐와`) 컨텍스트가 길어져도 일관성 유지.

**문제**: Agent SDK의 컨텍스트 윈도우를 초과하면 앞쪽 턴 정보를 잊어버림.
**해결**: 매 턴 Observation에 작업 요약을 포함. 진실의 원천은 대화 기록이 아니라 Observation.

```
Observation {
  position: {x, y, z}
  inventory: [...]

  task_summary: {
    goal: "철 64개 수집"
    progress: "47/64 완료"
    history: [
      "창고 좌표 확인 (12, 64, -30)",
      "광산으로 이동 (32, 15, -128)",
      "철광석 47개 채굴 완료",
      "곡괭이 교체 1회"
    ]
  }
}
```

## 세션 외 일관성

명령과 명령 사이 지식 유지. Agent SDK의 `query()`는 호출마다 새 세션이므로 파일 기반 메모리 사용.

### 저장 위치

월드 세이브 폴더 안에 저장 → 월드 삭제/복사 시 메모리도 함께 이동.

```
<minecraft>/saves/<world_name>/.agent/
└── agents/
    └── default/              # MVP는 단일 에이전트, 추후 멀티 에이전트 확장 가능
        ├── locations.json    # 이름 → 좌표 ("창고": {x,y,z})
        ├── preferences.json  # 플레이어 선호 ("다이아 곡괭이 우선")
        ├── facilities.json   # 시설 정보 ("AE2 컨트롤러 위치", "화로 8개")
        └── task-history.json # 최근 작업 로그
```

### 사용 흐름

```
세션 시작 시:
  메모리 파일 로드 → 프롬프트에 주입 → Agent가 맥락 파악

세션 중:
  새 정보 발견 시 → remember_location, remember_facility 등 tool 호출 → 메모리 파일 갱신

세션 종료 시:
  task-history에 작업 결과 기록
```

### 메모리 관련 tool (Agent Runtime)

| Tool | 설명 |
|---|---|
| `remember_location(name, x, y, z, note)` | 장소 기억 |
| `remember_facility(name, pos, type, note)` | 시설/기계 기억 |
| `remember_preference(key, value)` | 플레이어 선호 기억 |
| `recall(query)` | 메모리에서 관련 정보 검색 |
