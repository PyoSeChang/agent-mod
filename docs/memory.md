# 메모리 시스템

## 개요

메모리는 **skill과 분리된 독립 레이어**. Skill은 "뭘 참조해야 하는지"만 알고, 실제 데이터는 memory에 질의한다.

```
Skill: "철 곡괭이 제작"
  needs: memory.facilities["작업대"]  ← 좌표를 하드코딩하지 않음
  needs: memory.storage["철 보관"]    ← 어디서 꺼낼지 memory가 앎
```

같은 skill이 어떤 월드에서든 동작한다. 좌표는 memory에서 바뀌므로.

## 구조: Session vs Persistent

```
Memory
├── Session (휘발성, 현재 작업 중에만)
│   ├── working: 지금 하고 있는 일의 상태
│   │   "철 3/10개 채굴 완료, 다음 광맥 (30, -12, 50)"
│   ├── context: 이번 세션에서 관찰한 것
│   │   "아까 (100, 64, 200) 지나갈 때 좀비 무리 있었음"
│   └── conversation: 플레이어와의 대화 맥락
│       "플레이어가 남쪽은 건너뛰라고 했음"
│
└── Persistent (영구, 세션 간 유지, 월드별 저장)
    ├── locations: 이름 붙은 좌표
    │   {"집": {x,y,z}, "광산": {x,y,z}}
    ├── facilities: 시설물 + 유형
    │   {"작업대#1": {pos, type:"crafting_table"}}
    ├── storage: 어디에 뭐가 있는지
    │   {"메인 창고": {pos, contents:["철","다이아"]}}
    ├── preferences: 플레이어 성향
    │   {"채굴방식": "branch mining", "건축스타일": "중세"}
    ├── world_knowledge: 이 월드 고유 정보
    │   {"슬라임 청크": [...], "마을 위치": [...]}
    └── task_history: 과거 작업 기록
        [{task, result, turns, timestamp}]
```

## Session Memory

한 명령 수행 중 (`/agent 철 64개 캐와`) 컨텍스트가 길어져도 일관성 유지.

**문제**: Agent SDK의 컨텍스트 윈도우를 초과하면 앞쪽 턴 정보를 잊어버림.
**해결**: 매 턴 Observation에 작업 요약을 포함. 진실의 원천은 대화 기록이 아니라 Observation.

```json
{
  "task_summary": {
    "goal": "철 64개 수집",
    "progress": "47/64 완료",
    "history": [
      "창고 좌표 확인 (12, 64, -30)",
      "광산으로 이동 (32, 15, -128)",
      "철광석 47개 채굴 완료"
    ]
  }
}
```

## Persistent Memory

### 저장 위치

월드 세이브 폴더 안에 저장 → 월드 삭제/복사 시 메모리도 함께 이동.

```
<minecraft>/saves/<world_name>/.agent/
└── agents/
    └── default/
        ├── locations.json
        ├── facilities.json
        ├── storage.json
        ├── preferences.json
        ├── world_knowledge.json
        └── task_history.json
```

### 흐름

```
세션 시작 시:
  Persistent memory 로드 → 프롬프트에 주입 → Agent가 맥락 파악

세션 중:
  새 정보 발견 → memory tool 호출 → 파일 갱신
  Session memory는 Observation의 task_summary로 유지

세션 종료 시:
  task_history에 작업 결과 기록
```

### Memory Tools (MCP)

| Tool | 대상 | 설명 |
|---|---|---|
| `remember_location(name, x, y, z, note)` | locations | 장소 기억 |
| `remember_facility(name, pos, type, note)` | facilities | 시설/기계 기억 |
| `remember_storage(name, pos, contents)` | storage | 창고 내용물 기억 |
| `remember_preference(key, value)` | preferences | 플레이어 선호 기억 |
| `recall(query)` | 전체 | 메모리에서 관련 정보 검색 |

## 구현 우선순위

1. **MVP (현재)**: locations, facilities, preferences, task_history
2. **다음**: session memory (working, context), storage
3. **이후**: world_knowledge, 메모리 간 연관 검색
