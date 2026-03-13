# Scenario Registry

모든 시나리오의 인덱스. 상태가 `active`인 것만 테스트 대상.

| ID | 이름 | 카테고리 | 난이도 | 상태 | 추가일 |
|----|------|----------|--------|------|--------|
| S-G001 | 나무 채벌 (맨손) | gathering | unit | active | 2026-03-11 |
| S-F001 | 밭 일구기 (나무→호미→경작) | farming | integration | active | 2026-03-11 |
| S-F002 | 씨앗 심기 (상자→심기) | farming | integration | active | 2026-03-11 |
| S-X001 | 농사 전체 (채벌→호미→경작→심기) | composite | e2e | active | 2026-03-11 |

### 난이도 정의

- **unit**: 단일 액션 또는 도구 테스트
- **integration**: 여러 액션 조합 (craft→equip→use)
- **e2e**: 유저 명령 하나로 전체 워크플로우
