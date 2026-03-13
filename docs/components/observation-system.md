# Observation System (관찰)

Agent가 판단을 내리기 위해 필요한 월드 상태 정보.

## 스키마

```
Observation {
  // 자기 상태
  position: {x, y, z}
  inventory: [{slot: 0, item: "iron_ingot", count: 64}, ...]

  // 주변 환경
  nearby_blocks: [{pos, block_id, block_name}, ...]   // 반경 N 블록
  nearby_entities: [{type, pos, health, name}, ...]    // 반경 N 엔티티

  // 현재 열린 GUI (있을 경우)
  open_container: {type: "chest", slots: [...]}

  // 모드 정보 (Compat Layer가 제공)
  mod_data: {
    ae2_network: {channels_used: 24, channels_max: 32, ...},
    create_kinetic: {rpm: 128, stress: 512, ...}
  }

  // 메모리 시스템 (memory.md 참조)
  memories: {
    title_index: [                           // 전체 (global+agent) 거리순 정렬
      {id: "m001", category: "storage", title: "거점 상자", distance: 3.0, scope: "global"},
      {id: "m005", category: "skill", title: "다이아 채굴법", distance: -1, scope: "agent:alex"},
      ...
    ]
    auto_loaded: [                           // 카테고리별 규칙으로 content 주입
      {id: "m001", title: "거점 상자", content: "1층 상자 3개: ..."},
      ...
    ]
  }

  // 모드 정보 (Compat Layer가 제공)
  mod_data: {
    ae2_network: {channels_used: 24, channels_max: 32, ...},
    create_kinetic: {rpm: 128, stress: 512, ...}
  }
}
```
