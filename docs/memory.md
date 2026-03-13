# Memory System — Unified Knowledge Architecture

## Core Concept

모든 지식(장소, 시설, 자원, 절차, 선호)을 **하나의 엔트리 타입**으로 통합.
Claude Code의 skill 시스템과 동일한 메커니즘: description 항상 로드 → 매칭 시 content 주입.

## Architecture

```
[Player command] → auto-load 판단
                     ↓
              Title Index (항상 로드, 거리순 정렬)
              + Category별 auto-load 규칙
                     ↓
              매칭된 entry의 content → 프롬프트 주입
                     ↓
              Brain이 행동 결정
```

```
[GUI Screen] ←→ [MemoryManager (Java)] ←→ memory.json
                        ↕
               [AgentHttpServer 엔드포인트]
                        ↕
               [MCP tools: remember / recall / search]
                        ↕
                     [Brain]
```

## MemoryEntry Schema

```json
{
  "id": "uuid",
  "title": "거점 상자 - 철/다이아 보관",
  "description": "거점 지하 창고. 철괴 64+, 다이아 12개 보관 중",
  "content": "1층 상자 3개: 좌측=철/금, 중앙=다이아/에메랄드, 우측=레드스톤\n2층은 식량 전용",
  "category": "storage",
  "tags": ["iron", "diamond", "base"],
  "location": {
    "type": "point",
    "x": 128, "y": 64, "z": -200,
    "radius": 5
  },
  "scope": "global",
  "createdAt": "2026-03-12T10:00:00Z",
  "updatedAt": "2026-03-12T15:30:00Z",
  "loadedAt": "2026-03-12T15:45:00Z"
}
```

### Fields

| Field | 역할 |
|-------|------|
| `title` | Title Index에 표시. 한 줄 요약 |
| `description` | auto-load 매칭에 사용. 키워드 풍부하게 |
| `content` | 상세 정보. 매칭 시에만 주입 |
| `category` | auto-load 규칙 결정 + GUI 필터 |
| `tags` | 검색용 키워드 |
| `location` | point + radius 또는 area (두 코너) |
| `scope` | `global` (월드 공유) 또는 `agent:{id}` (개별) |
| `createdAt` | 생성 시각 |
| `updatedAt` | 마지막 수정 시각 |
| `loadedAt` | 마지막으로 brain에 주입된 시각. 자주 로드되는 기억 vs 잊혀진 기억 구분 |

### Location Types

```json
// Point + radius
{ "type": "point", "x": 128, "y": 64, "z": -200, "radius": 5 }

// Area (두 코너)
{ "type": "area", "x1": 100, "z1": -210, "x2": 156, "z2": -190, "y": 64 }
```

## Categories

| Category | 설명 | auto-load 규칙 |
|----------|------|---------------|
| `storage` | 창고, 상자, 자원 보관 | 공간 근접 |
| `facility` | 화로, 작업대, 인챈트 등 시설 | 공간 근접 |
| `area` | 농장, 광산, 거점 등 영역 | 공간 근접 |
| `event` | 사건, 발견, 상태 변화 | 시간 최신순 상위 N개 |
| `preference` | 플레이어 선호, 규칙 | 항상 로드 |
| `skill` | 절차적 지식 (채굴법, 건축법 등) | description ↔ 명령 매칭 |

## Auto-Load Mechanism

Title Index + description은 **항상** 프롬프트에 포함 (agent 위치 기준 거리순 정렬):

```
## Memories (nearest first)
#07 [storage] 거점 상자 - 철/다이아 보관          (3m) 거점 지하 창고. 철괴 64+, 다이아 12개
#12 [facility] 화로 2기                           (5m) 제련용 화로. 석탄 상시 보충 필요
#03 [area] 밀밭 9x9                               (12m) 자동 수확 미구현. 수동 수확 필요
#15 [storage] 남쪽 광산 입구 상자                   (87m) 광산 탐사용 장비 보관
#01 [area] 스폰 지점                               (142m) 초기 스폰. 특별한 시설 없음
#20 [skill] 다이아몬드 채굴법                       (-) Y -59 strip mining 절차
#21 [preference] 채굴 선호                         (-) branch mining, 3칸 간격
```

Content auto-load 규칙 (description이 아닌 **full content** 주입):

```
1. preference → 항상 content 로드
2. skill     → description ↔ 현재 명령 키워드 매칭 시
3. storage / facility / area → 공간 근접 (반경 N블록 내)
4. event     → 시간 최신순 상위 N개
5. 그 외     → brain이 recall(id)로 명시적 조회
```

## Scope: Global vs Agent

| | Global | Agent |
|---|--------|-------|
| 소유자 | 없음 (월드) | 특정 agent ID |
| 공유 | 모든 agent 접근 | 본인만 |
| 예시 | 상자 위치, 화로 위치, 채굴법 | "나는 건축 담당", 개인 작업 큐 |

## Storage

```
<world>/.agent/
├── memory.json          # Global memory
└── agents/
    └── {agent-id}/
        └── memory.json  # Agent-specific memory
```

JSON 선택 이유: 프로그래매틱 파싱, 필드별 쿼리, category/tag 필터링 용이.

## MCP Tools

| Tool | 설명 |
|------|------|
| `remember(title, description, content, category, tags, location?)` | 기억 생성. location 미지정 시 현재 위치 |
| `update_memory(id, fields...)` | 기억 수정 (부분 업데이트) |
| `forget(id)` | 기억 삭제 |
| `recall(id)` | 단건 상세 조회 (full content) |
| `search_memory(query, category?)` | 키워드 검색 (title + description + tags) |

## GUI (Forge Screen)

| Screen | 역할 |
|--------|------|
| `MemoryListScreen` | 스코프 탭 (All/Global/에이전트별) + 카테고리 탭 + 검색 + 거리순 리스트 |
| `MemoryEditScreen` | title, description, content, category, tags, location, scope 편집. 신규/수정/삭제 |

열기: 키바인드 `M`

### Boundary 입력

- **Point**: "현재 위치 사용" 버튼 (기본값) + radius 슬라이더
- **Area**: 월드에서 블록 2개 우클릭으로 코너 지정 (마크 모드)

### 스코프 선택

- MemoryListScreen 상단: `[All] [Global] [alex] [steve] ...` 탭 (에이전트 목록 동적 로드)
- MemoryEditScreen: `[global] [alex] [steve] ...` 스코프 버튼, 부모 화면 스코프 상속

## Implementation Order

1. `MemoryEntry` 모델 + `MemoryManager` (Java 싱글턴, CRUD + JSON persistence)
2. HTTP 엔드포인트 (`/memory/*`)
3. MCP tools (`remember`, `recall`, `search_memory`, `update_memory`, `forget`)
4. Auto-load 로직 (observation에 title index + 매칭 content 주입)
5. `MemoryListScreen` (GUI)
6. `MemoryEditScreen` (GUI + 마크 모드)
