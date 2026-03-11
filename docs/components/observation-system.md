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

  // 세션 내 일관성 (memory.md 참조)
  task_summary: {
    goal: "철 64개 수집"
    progress: "47/64 완료"
    history: [...]
  }
}
```
