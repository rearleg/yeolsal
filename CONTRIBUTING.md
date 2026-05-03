# Contributing

이 저장소는 BE(Spring Boot 3.3 / JDK 21) + FE(Expo / React Native)로 나뉘어 있고, PR은 보통 stack 형태로 쌓입니다. 이 문서는 그 stack을 main으로 안전하게 흘려보내는 절차와, 과거에 한 번 운영을 다운시킨 머지 사고가 재발하지 않도록 지키는 룰만 모았습니다.

## Stack PR 머지 절차 (가장 중요)

지난번에 stack PR(#25/#27/#28/#30/#31/#32/#33)을 GitHub UI에서 그냥 squash merge했더니 squash commit이 stack base 브랜치로 떨어지고 main에는 도달하지 못해, **V7/V8 마이그레이션이 prod에 안 들어가고 그 위 모든 BE/FE 변경이 누락되는 사고**가 있었습니다 (참고: PR #36).

그 사고를 막는 룰:

1. **stack 머지는 반드시 base 브랜치 chain의 가장 아래에서 위로**.
2. **각 PR을 머지하기 직전에 base가 main인지 확인**. base가 다른 stack 브랜치라면, 그 base 브랜치를 먼저 머지하거나, 이 PR의 base를 main으로 명시적으로 변경.
3. **head 브랜치 자동 삭제(`Delete branch` 체크)를 켜둔 채 머지**. base 브랜치가 사라지면 GitHub이 stack 위 PR의 base를 자동으로 main으로 갱신해 줍니다.
4. **합치기 전에 항상 `gh pr view <N> --json baseRefName,mergeStateStatus`로 base와 mergeable 상태 확인**. base가 main이 아니면 멈추고 위 단계로 돌아갈 것.

운영 배포 직전, 머지된 PR이 실제로 main에 도달했는지는 다음 명령으로 확인:

```bash
git fetch origin main
gh pr list --state merged --search "merged:>=<날짜>" --json number,mergeCommit \
  --jq '.[] | "\(.number) \(.mergeCommit.oid)"'
git merge-base --is-ancestor <merge-commit-oid> origin/main && echo OK || echo MISSING
```

`MISSING`이 떨어지면 그 PR의 squash commit은 main이 아니라 stack base 브랜치에 들어갔을 가능성이 높습니다. 통합 PR(예: PR #36 `chore: collapse stack PRs`)을 다시 만들어 stack tip을 main에 직접 통합하세요.

## 일반 PR 절차

1. main 기준 새 브랜치 (`feat/...`, `fix/...`, `chore/...`).
2. TDD가 가능한 작업이면 RED → GREEN → 필요 시 Refactor 순으로 commit.
3. BE 변경: `./gradlew test` 통과까지 push 금지.
4. FE 변경: `npx tsc --noEmit` clean + `npx jest` 통과.
5. 운영에 영향이 큰 변경(마이그레이션, 보안, 인증 wiring)은 PR 본문에 "머지 후 사용자 액션" 섹션으로 명령을 적어두세요.

## BE 마이그레이션

- `BE/src/main/resources/db/migration/V<N>__<slug>.sql` 형식. 버전 번호는 빈 슬롯 중 가장 작은 정수.
- Flyway는 1회만 실행되니 멱등 SQL 권장 (`drop ... if exists`, `insert ... on conflict do nothing`).
- partial unique expression index를 추가하면 같은 형태의 `INSERT ... ON CONFLICT ... WHERE pred DO ...` 절이 service 코드와 정확히 매칭돼야 합니다 (예: V8/V9의 MILESTONE dedup).

## 운영 다운 시 진단 우선순위

1. `docker compose exec api cat /app/COMMIT` — 어느 commit이 떠 있는지 (PR #40 머지 후 의미 있음).
2. `docker compose logs api --since 5m | grep -iE "\[chat\]|\[db\]|\[validation\]|exception" | tail -40` — `ApiExceptionHandler`의 root-cause 로그 1줄에 답이 있을 가능성이 큼.
3. `docker compose exec postgres psql -U yeosal -d yeosal -c "select version, success from flyway_schema_history order by installed_rank desc limit 5;"` — 마이그레이션이 실제로 적용됐는지.
4. ApplicationContext 부팅 실패가 의심되면 `docker compose logs api --since 5m | grep -iE "Error creating bean|No default constructor"` — 과거 사례: PR #34의 `RateLimitFilter @Autowired` 누락.
