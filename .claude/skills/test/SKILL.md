---
name: test
description: Agent test harness. Claude가 HTTP bridge를 통해 에이전트를 직접 조작하고, 시나리오 실행 → 데이터 수집 → 정성적 평가 → 결과 기록을 수행한다. Use when the user says "테스트", "test", "시나리오 실행", "품질 테스트", or invokes /test with a subcommand (run, list, create, edit, delete, results, report).
---

# Agent Test Harness

Claude가 HTTP bridge로 에이전트를 조작하고, 시나리오 기반 정성적 품질 평가를 수행하는 skill.

**핵심 전제**: 에이전트 행동의 품질 평가는 정성적이다. 코드가 자동 판정할 수 없다. Claude가 이벤트 흐름, observation, 로그를 읽고 맥락을 이해하여 평가한다.

## Subcommands

### `/test run {scenario-id}`

시나리오를 실행하고 정성적 평가 결과를 기록한다.

#### Phase 0 — Server Lifecycle

서버가 이미 실행 중인지 확인하고, 아니면 자동으로 시작한다.

1. **bridge 존재 확인**:
   ```bash
   cat run/.agent/bridge-server.json 2>/dev/null
   ```
   파일이 있으면 연결 테스트:
   ```bash
   curl -s --max-time 3 http://localhost:{port}/session/info
   ```
   연결 성공 시 → Phase 1로 건너뛴다 (서버 이미 실행 중).

2. **서버 시작** (연결 실패 또는 bridge 파일 없음):
   ```bash
   # 이전 bridge 파일 삭제 (stale port 방지)
   rm -f run/.agent/bridge-server.json

   # 서버를 백그라운드로 시작
   ./gradlew runServer
   ```
   `run_in_background: true`로 실행한다. 서버는 백그라운드에서 기동된다.

3. **서버 준비 대기**:
   bridge-server.json이 생성될 때까지 폴링 (최대 120초):
   ```bash
   cat run/.agent/bridge-server.json 2>/dev/null
   ```
   5초 간격으로 확인. 파일이 생기면 port를 읽고 연결 테스트:
   ```bash
   curl -s --max-time 3 http://localhost:{port}/session/info
   ```
   120초 내 연결 안 되면 "서버 시작 실패" 보고 후 중단.

4. **서버 시작 여부 기록** — Cleanup(Phase 7)에서 서버를 종료할지 결정하기 위해.

**서버 설정 파일** (사전 구성됨):
- `run/eula.txt` — `eula=true`
- `run/server.properties` — `level-name=test-world`, `online-mode=false`, `difficulty=peaceful`

#### Phase 1 — Discovery

1. bridge port는 Phase 0에서 이미 확인됨.

2. 시나리오 로드: `docs/quality/scenarios/` 에서 해당 ID를 찾아 읽는다.
   - S-G → `gathering.md`
   - S-F → `farming.md`
   - S-X → `composite.md`
   - 카테고리 prefix로 파일 매핑. `_registry.md`에서 ID 확인 가능.

3. 버전 확인:
   ```bash
   git describe --tags --always
   ```

#### Phase 2 — Setup

1. 기존 에이전트 정리 (충돌 방지):
   ```bash
   curl -s -X POST http://localhost:{port}/agent/test-agent/despawn -d '{}' -H 'Content-Type: application/json'
   ```
   (이미 없으면 무시)

2. 에이전트 스폰:
   ```bash
   curl -s -X POST http://localhost:{port}/agent/test-agent/spawn -d '{"name":"test-agent"}' -H 'Content-Type: application/json'
   ```

3. Config 설정 (시나리오에 명시된 경우):
   ```bash
   curl -s -X POST http://localhost:{port}/agent/test-agent/config -d '{"gamemode":"CREATIVE"}' -H 'Content-Type: application/json'
   ```

4. 초기 메모리 세팅 (시나리오에 명시된 경우):
   ```bash
   curl -s -X POST http://localhost:{port}/memory/create -d '{...}' -H 'Content-Type: application/json'
   ```

5. **pre-test observation 기록** — 시작 상태 캡처:
   ```bash
   curl -s http://localhost:{port}/agent/test-agent/observation
   ```
   위치, 인벤토리, 주변 블록을 기록해둔다.

#### Phase 3 — Execute

1. 시작 시각 기록 (wall clock).

2. 명령 전송:
   ```bash
   curl -s -X POST http://localhost:{port}/agent/test-agent/tell -d '{"message":"{command}"}' -H 'Content-Type: application/json'
   ```
   - composite 시나리오(S-X)의 경우 여러 명령을 순서대로 전송. 각 명령 사이에 완료 대기.

3. **Claude가 이벤트 흐름을 관찰하면서 완료를 판단한다.**
   - 주기적으로 상태 확인:
     ```bash
     curl -s http://localhost:{port}/agent/test-agent/status
     curl -s http://localhost:{port}/events/history
     ```
   - 판단 기준 (Claude의 재량):
     - `runtimeRunning`이 false가 됨
     - 일정 시간 동안 새 액션 이벤트가 없음
     - 에이전트가 최종 메시지를 보냄 (CHAT 이벤트)
   - 시나리오의 `timeout_seconds` (기본 300초) 초과 시 중단.
   - **자동 판정 로직이 아니다.** Claude가 상황을 보고 "끝났다"고 판단한다.

#### Phase 4 — 데이터 수집

1. **post-test observation**:
   ```bash
   curl -s http://localhost:{port}/agent/test-agent/observation
   ```
   인벤토리 변화, 위치 변화, 주변 블록 변화를 pre-test와 비교.

2. **이벤트 히스토리**:
   ```bash
   curl -s http://localhost:{port}/events/history
   ```
   이 에이전트의 이벤트만 필터링. ACTION_COMPLETED, ACTION_FAILED, TOOL_CALL, THOUGHT 등을 분석.

3. **JSONL 로그** (선택):
   ```bash
   node scripts/parse-agent-log.js --summary
   node scripts/parse-agent-log.js --failures
   ```
   상세 substep 데이터가 필요할 때 사용.

#### Phase 5 — 정성적 평가

시나리오의 **"측정 기준"** (무엇을 수집할지)과 **"평가 관점"** (무엇을 판단할지)을 참고하여:

1. **각 평가 관점별 판단 + 근거 작성**
   - 관점별로 "양호", "개선 필요", "실패" 등 자유 형식 판정
   - 근거는 수집한 데이터에서 구체적 사례를 인용

2. **정량 지표 요약**
   - 총 액션 수, 성공/실패 비율, 소요 시간, 턴 수 등
   - 이벤트 히스토리에서 카운트

3. **잘한 점 / 문제점**
   - 전반적 소감

4. **발견 이슈 식별**
   - 새 이슈 발견 시 I-NNN 번호 부여
   - CHANGELOG에서 마지막 이슈 번호 확인 후 다음 번호 사용

5. **이전 결과 비교** (있으면)
   - `docs/quality/test-results/` 에서 같은 시나리오의 이전 결과 파일을 읽어 비교
   - 개선/악화 판단

#### Phase 6 — Record

결과 마크다운 작성:
```
docs/quality/test-results/{version}/{YYYY-MM-DD}_{scenario-id}.md
```

결과 파일 포맷:

```markdown
# {scenario-id}: {scenario-name} — {version}

- **일시**: {YYYY-MM-DD HH:MM}
- **버전**: {version} (commit `{hash}`)
- **에이전트**: {agent-name}

## 수집 데이터

### 상태 변화
| 항목 | before | after |
|------|--------|-------|
| 위치 | ... | ... |
| 인벤토리 | ... | ... |

### 액션 기록
| # | 액션 | 결과 | 비고 |
|---|------|------|------|
| 1 | ... | OK/FAIL | ... |

### 정량 지표
| 지표 | 값 |
|------|-----|
| 총 액션 | N |
| 성공/실패 | N/N |
| 소요시간 | Ns |
| 턴 수 | N |

## 평가

### {관점 1}
**{판정}** — {근거}

### {관점 2}
**{판정}** — {근거}

...

## 발견 이슈
- [ ] I-NNN: {description} ({component})

## 이전 비교
{이전 결과와 비교 분석. 없으면 "첫 실행" 표기}
```

#### Phase 7 — Cleanup

1. 에이전트 정리:
   ```bash
   curl -s -X POST http://localhost:{port}/agent/test-agent/stop -d '{}' -H 'Content-Type: application/json'
   curl -s -X POST http://localhost:{port}/agent/test-agent/despawn -d '{}' -H 'Content-Type: application/json'
   ```

2. 서버 종료 (Phase 0에서 Claude가 시작한 경우만):
   ```bash
   # Minecraft 서버에 stop 명령 전송 (graceful shutdown)
   # gradlew runServer 프로세스의 stdin에 "stop" 입력 또는 프로세스 종료
   ```
   사용자가 이미 실행 중이던 서버는 종료하지 않는다.

---

### `/test list`

등록된 시나리오 목록을 표시한다.

1. `docs/quality/scenarios/_registry.md` 읽기.
2. 테이블 형식으로 표시 (ID, 이름, 카테고리, 난이도, 상태).
3. 최근 테스트 결과가 있으면 마지막 실행일도 표시.

---

### `/test create`

새 시나리오를 추가한다.

1. 사용자에게 질문: 카테고리, 명령, 전제조건, 측정 기준.
2. ID 자동 부여:
   - `S-G` = gathering, `S-F` = farming, `S-C` = crafting, `S-B` = building, `S-A` = combat, `S-X` = composite
   - `_registry.md`에서 해당 카테고리의 마지막 번호 확인 후 +1.
3. 평가 관점을 Claude가 시나리오 내용 기반으로 제안, 사용자 확인.
4. 해당 카테고리 마크다운 파일에 시나리오 추가.
5. `_registry.md`에 행 추가.

---

### `/test edit {scenario-id}`

기존 시나리오를 수정한다.

1. 해당 시나리오가 있는 마크다운 파일 찾기.
2. 사용자와 대화하며 수정 사항 확인.
3. 파일 업데이트.

---

### `/test delete {scenario-id}`

시나리오를 비활성화한다.

1. `_registry.md`에서 해당 ID의 상태를 `retired`로 변경.
2. 시나리오 마크다운에서 제목에 `(retired)` 표시.
3. 파일 자체를 삭제하지는 않는다 (이력 보존).

---

### `/test results {scenario-id}`

최근 결과를 조회하고 이전과 비교한다.

1. `docs/quality/test-results/` 하위에서 해당 시나리오 ID가 포함된 파일 검색.
2. 가장 최근 결과 표시.
3. 이전 결과가 있으면 비교 분석 제공.

---

### `/test report {version}`

해당 버전의 전체 테스트 결과를 CHANGELOG에 삽입할 수 있는 마크다운으로 요약한다.

1. `docs/quality/test-results/{version}/` 하위의 모든 결과 파일 읽기.
2. 시나리오별 판정 요약 테이블 생성.
3. 주요 발견 이슈 목록.
4. 이전 버전 대비 변화 요약.

## References

- HTTP bridge API: `src/main/java/com/pyosechang/agent/network/AgentHttpServer.java`
- Observation 구조: `src/main/java/com/pyosechang/agent/core/ObservationBuilder.java`
- Event types: `src/main/java/com/pyosechang/agent/event/EventType.java`
- 시나리오 정의: `docs/quality/scenarios/`
- 시나리오 레지스트리: `docs/quality/scenarios/_registry.md`
- 결과 저장: `docs/quality/test-results/`
- 로그 파서: `scripts/parse-agent-log.js`
- bridge port 파일: `run/.agent/bridge-server.json`
