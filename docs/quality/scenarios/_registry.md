# Scenario Registry

모든 시나리오의 인덱스. 상태가 `active`인 것만 테스트 대상.

| ID | 이름 | 카테고리 | 난이도 | 상태 | 추가일 |
|----|------|----------|--------|------|--------|
| S-G001 | 나무 채벌 (맨손) | gathering | unit | active | 2026-03-11 |
| S-F001 | 밭 일구기 (나무→호미→경작) | farming | integration | active | 2026-03-11 |
| S-F002 | 씨앗 심기 (상자→심기) | farming | integration | active | 2026-03-11 |
| S-X001 | 농사 전체 (채벌→호미→경작→심기) | composite | e2e | active | 2026-03-11 |
| S-B001 | 평탄화 — 흙 바닥 채우기 | building | integration | active | 2026-03-17 |
| S-B002 | 벽 쌓기 — 수직 블록 배치 | building | integration | active | 2026-03-17 |
| S-B003 | 플레이어 접근 — 사회적 거리 | building | unit | active | 2026-03-17 |

### 난이도 정의

- **unit**: 단일 액션 또는 도구 테스트
- **integration**: 여러 액션 조합 (craft→equip→use)
- **e2e**: 유저 명령 하나로 전체 워크플로우
