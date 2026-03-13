# Schedule System

## 개요

에이전트에게 시간/조건 기반 자동 작업을 지시하는 시스템.
메모리의 한 카테고리(`schedule`)로 통합되며, title index에서는 제외.
agent-manager가 등록/관리하고, 트리거 시 해당 agent에게 prompt를 전달한다.

## 스케줄 타입

### 1. TIME_OF_DAY — 게임 시계 기반

마인크래프트 하루(24,000틱) 중 특정 시각에 발동.

| 게임 틱 | 시각 | 의미 |
|---------|------|------|
| 0 | 06:00 | 일출 |
| 6000 | 12:00 | 정오 |
| 12000 | 18:00 | 일몰 |
| 18000 | 00:00 | 자정 |

```json
{
  "type": "TIME_OF_DAY",
  "triggerConfig": {
    "timeOfDay": 0,
    "repeatDays": 1
  }
}
```

- `timeOfDay`: 0~23999 (게임 내 틱)
- `repeatDays`: N일마다 반복. 0 = 1회성
- 판정: `level.getDayTime() % 24000 == timeOfDay` && 일수 조건

### 2. INTERVAL — 틱 간격 기반

등록 시점부터 N틱마다 반복 발동.

```json
{
  "type": "INTERVAL",
  "triggerConfig": {
    "intervalTicks": 6000,
    "repeat": true
  }
}
```

- `intervalTicks`: 발동 간격 (틱 단위, 20틱 = 1초)
- `repeat`: false면 1회만
- 판정: `(currentTick - registeredTick) % intervalTicks == 0`

### 3. OBSERVER — 블록/엔티티 이벤트 기반

Forge 이벤트를 구독하여 조건 충족 시 발동.

```json
{
  "type": "OBSERVER",
  "triggerConfig": {
    "observers": [
      { "pos": [100, 64, 200], "event": "crop_grow", "condition": "age=7" },
      { "pos": [101, 64, 200], "event": "crop_grow", "condition": "age=7" },
      { "pos": [102, 64, 200], "event": "crop_grow", "condition": "age=7" }
    ],
    "threshold": 7,
    "total": 10,
    "repeat": true,
    "resetOnTrigger": true
  }
}
```

- `observers`: 감시 대상 목록 (위치 + 이벤트 타입 + 조건)
- `threshold`: N개 이상 ON이면 트리거
- `resetOnTrigger`: 트리거 후 observer 상태 초기화 (반복용)

## 지원 이벤트 타입 (v1)

### 블록 이벤트

| event 문자열 | Forge 이벤트 | 감지 내용 |
|-------------|-------------|-----------|
| `crop_grow` | `CropGrowEvent.Post` | 작물 성장 |
| `sapling_grow` | `SaplingGrowTreeEvent` | 묘목 → 나무 |
| `block_break` | `BlockEvent.BreakEvent` | 블록 파괴 |
| `block_place` | `BlockEvent.EntityPlaceEvent` | 블록 설치 |
| `farmland_trample` | `BlockEvent.FarmlandTrampleEvent` | 농지 밟힘 |

### 엔티티 이벤트

| event 문자열 | Forge 이벤트 | 감지 내용 |
|-------------|-------------|-----------|
| `baby_spawn` | `BabyEntitySpawnEvent` | 새끼 탄생 |
| `entity_death` | `LivingDeathEvent` | 엔티티 사망 |
| `entity_spawn` | `EntityJoinLevelEvent` | 엔티티 스폰 |

### 환경 이벤트

| event 문자열 | Forge 이벤트 | 감지 내용 |
|-------------|-------------|-----------|
| `explosion` | `ExplosionEvent.Detonate` | 폭발 |
| `sleep_finished` | `SleepFinishedTimeEvent` | 밤 스킵 |

> v2에서 모드 호환 이벤트 추가 예정 (Create, AE2 등)

## 데이터 모델

`ScheduleEntry extends MemoryEntry`

```java
public class ScheduleEntry extends MemoryEntry {
    private String type;              // TIME_OF_DAY, INTERVAL, OBSERVER
    private JsonObject triggerConfig; // 타입별 설정
    private String targetAgent;       // 트리거 시 메시지를 받을 agent
    private String promptMessage;     // 트리거 시 agent에게 전달할 메시지
    private boolean enabled;          // 활성/비활성
    private long registeredTick;      // 등록 시점의 서버 틱 (INTERVAL 기준점)
    private long lastTriggeredTick;   // 마지막 트리거 시점

    // category는 항상 "schedule"로 고정
    // title index에서 제외
    // visibleTo로 스코핑 (특정 agent만 볼 수 있는 스케줄)
}
```

## 메모리 통합

- category = `"schedule"` 고정
- MemoryManager의 기존 CRUD, persist, visibleTo 그대로 사용
- **title index에서 제외** — agent의 observation에 자동 주입하지 않음
- 트리거 시점에 agent-manager가 직접 주입

## 트리거 흐름

```
[Java] 게임 틱 / Forge 이벤트
  │
  ├── TIME_OF_DAY: AgentTickHandler에서 매 틱 체크
  │     dayTime % 24000 == timeOfDay && 일수 조건
  │
  ├── INTERVAL: AgentTickHandler에서 매 틱 체크
  │     (currentTick - registeredTick) % intervalTicks == 0
  │
  └── OBSERVER: Forge @SubscribeEvent 핸들러
        이벤트 발생 → 해당 pos의 observer 조건 체크
        → threshold 이상 ON → 트리거
  │
  ▼
[Java → HTTP → Agent Manager 런타임]
  "schedule {id} triggered"
  │
  ▼
[Agent Manager] SDK query
  → 판단: 어떤 agent에게 전달할지
  → promptMessage를 해당 agent에게 intervention으로 전달
  │
  ▼
[Agent "alex"] 런타임 실행
  → 실제 행동 수행 (move, harvest, etc.)
```

## Observer 내부 구현

```
InvisibleObserver (데이터만, 월드에 엔티티/블록 없음)
  ├── watchPos: BlockPos
  ├── eventType: String ("crop_grow", "entity_death", ...)
  ├── condition: String ("age=7", "type=zombie", ...)
  ├── triggered: boolean
  └── belongsTo: scheduleId

ObserverManager
  ├── Forge 이벤트 핸들러 등록 (모드 초기화 시 1회)
  ├── 이벤트 수신 → pos/entity 매칭 → condition 체크 → triggered 갱신
  └── ScheduleManager에 threshold 도달 알림
```

## Persist

```
<world>/.agent/
├── memory.json          # 기존 메모리 + ScheduleEntry (category="schedule")
└── observers.json       # observer 상태 (triggered 등 런타임 상태)
```

ScheduleEntry는 memory.json에 통합 저장.
Observer 런타임 상태(triggered)는 별도 파일로 분리 (서버 재시작 시 리셋 가능).

## 예시 시나리오

### "매일 아침 밀 수확"

```
유저: /am "매일 아침 alex가 밀 농장 수확하도록 해줘"

agent-manager 판단:
  1. create_schedule({
       type: "TIME_OF_DAY",
       triggerConfig: { timeOfDay: 0, repeatDays: 1 },
       targetAgent: "alex",
       promptMessage: "밀 농장(100,64,200 ~ 110,64,210)에 가서 다 자란 밀을 수확하고 다시 심어"
     })

매일 게임 내 아침(tick 0):
  Java → agent-manager: "schedule triggered"
  agent-manager → alex: "밀 농장에 가서..."
  alex: move_to → 수확 → 재파종
```

### "밀 70% 이상 자라면 수확"

```
유저: /am "밀 농장에 observer 달아서 70% 자라면 alex가 수확하게 해"

agent-manager 판단:
  1. register_observer (농장 영역 10칸에 observer 배치)
  2. create_schedule({
       type: "OBSERVER",
       triggerConfig: { observers: [...], threshold: 7, total: 10, repeat: true },
       targetAgent: "alex",
       promptMessage: "밀 농장 수확 시기. 다 자란 밀 수확하고 다시 심어"
     })

밀 7칸 이상 age=7 도달:
  Java observer → threshold 충족 → agent-manager
  agent-manager → alex: "밀 농장 수확 시기..."
  alex: move_to → 수확 → 재파종
```
