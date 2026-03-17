# Composite Scenarios

여러 카테고리를 결합하는 end-to-end 시나리오.

## S-X001: 농사 전체 (채벌→호미→경작→심기)

- **전제조건**: 에이전트 근처에 나무 + 흙 + 씨앗 든 상자
- **명령**: 2단계로 실행
  1. "나무 캐서 호미 만들고 주변 땅 경작해"
  2. "상자에서 씨앗 꺼내서 심어"
- **측정 기준**:
  - 총 턴 수 (두 런 합산), 총 비용
  - 전체 성공 여부 (farmland 위에 wheat_crops 존재)
  - 새 도구 활용도: mine_area, use_item_on_area, execute_sequence 사용 횟수
  - 불필요 액션 수: 중복 get_observation, 수동 pickup_items, 반복 equip
- **관련 컴포넌트**: `actions`, `brain`, `body`

### 평가 관점

1. **목표 달성** — 최종적으로 farmland 위에 wheat_crops가 존재하는가? 전체 워크플로우 완료?
2. **단계 간 연결** — 1단계 결과물(호미, 경작지)이 2단계에서 정확히 활용되었는가?
3. **도구 선택** — mine_area, use_item_on_area, execute_sequence 등 효율적 도구 활용도
4. **행동 효율** — 불필요 액션 수 (중복 get_observation, 수동 pickup_items, 반복 equip)
5. **실패 복구** — 복합 실패 시 전체 흐름 유지 능력. 한 단계 실패가 다음 단계에 영향 미쳤는가?
