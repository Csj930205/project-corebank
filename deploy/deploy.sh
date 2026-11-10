#!/usr/bin/env bash
# 배포: 이미지 받기 → DB 마이그레이션 → 서버 교체 → 상태 확인. 새 서버가 정상이 아니면 이전 이미지로 되돌린다.
#
#   deploy/deploy.sh               GHCR 의 latest (main 의 마지막 성공 빌드)
#   deploy/deploy.sh <커밋 sha>    그 커밋의 이미지. 예전 버전으로 되돌릴 때도 이렇게 한다
#   deploy/deploy.sh --local       GHCR 대신 지금 코드로 jar 와 이미지를 직접 만들어 배포(테스트는 건너뜀)
#   deploy/deploy.sh --image <이름> 이미 있는 이미지를 그대로 배포(되돌리기 시험 등). 없으면 받는다
#
# 설정은 프로젝트 폴더의 .env (compose 가 읽는다). 서버 포트는 APP_PORT(기본 8080).
#
# 되돌리기가 되는 이유: 서버는 시작할 때 DB 버전을 확인하는데, 자기보다 새 마이그레이션(future)은 허용한다.
# 그래서 마이그레이션 뒤에 이전 이미지로 돌아가도 뜬다. 단, 새 마이그레이션이 이전 코드와 호환되어야 한다
# (열·테이블 삭제나 이름 변경은 한 번에 하지 않고, 추가 → 코드 전환 → 다음 배포에서 삭제 순으로 나눈다).
set -euo pipefail

cd "$(dirname "$0")/.."

REPOSITORY=ghcr.io/csj930205/project-corebank
APP_PORT=${APP_PORT:-8080}
HEALTH_URL="http://localhost:${APP_PORT}/actuator/health"
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-90}

log() { printf '\n[deploy] %s\n' "$*"; }

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  until curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; do
    if (( SECONDS >= deadline )); then
      return 1
    fi
    sleep 2
  done
}

# 1. 배포할 이미지 정하기
if [[ "${1:-}" == "--local" ]]; then
  IMAGE="project-corebank:local-$(git rev-parse --short HEAD)"
  log "jar 와 이미지를 직접 만든다: $IMAGE"
  ./gradlew bootJar -q
  docker build -q -t "$IMAGE" . > /dev/null
elif [[ "${1:-}" == "--image" ]]; then
  IMAGE="${2:?--image 뒤에 이미지 이름이 필요합니다}"
  docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull -q "$IMAGE" > /dev/null
  log "이미지: $IMAGE"
else
  IMAGE="${REPOSITORY}:${1:-latest}"
  log "이미지 받기: $IMAGE"
  docker pull -q "$IMAGE" > /dev/null
fi
export COREBANK_IMAGE="$IMAGE"

# 지금 돌고 있는 서버의 이미지. 새 서버가 실패하면 이걸로 되돌린다.
PREVIOUS_IMAGE=$(docker compose --profile deploy ps -q app 2>/dev/null \
  | xargs -r docker inspect --format '{{.Config.Image}}' 2>/dev/null || true)
log "지금 서버: ${PREVIOUS_IMAGE:-없음}"

# 2. DB 가 떠 있는지 확인하고 마이그레이션. 실패하면 서버는 건드리지 않고 멈춘다.
docker compose up -d --wait oracle
log "마이그레이션 (테이블 소유자)"
docker compose --profile deploy run --rm migrate

# 3. 서버 교체. 마이그레이션은 위에서 했으니 --no-deps 로 서버만 다시 만든다.
log "서버 교체 → $IMAGE"
docker compose --profile deploy up -d --no-deps app

# 4. 상태 확인. 실패하면 이전 이미지로 되돌린다.
if wait_healthy; then
  log "배포 완료: $IMAGE ($HEALTH_URL UP)"
  exit 0
fi

log "새 서버가 ${HEALTH_TIMEOUT}초 안에 정상이 되지 않았다. 최근 로그:"
docker compose --profile deploy logs --tail 40 app || true
if [[ -n "$PREVIOUS_IMAGE" && "$PREVIOUS_IMAGE" != "$IMAGE" ]]; then
  log "이전 이미지로 되돌린다: $PREVIOUS_IMAGE"
  COREBANK_IMAGE="$PREVIOUS_IMAGE" docker compose --profile deploy up -d --no-deps app
  if wait_healthy; then
    log "되돌리기 완료: $PREVIOUS_IMAGE"
  else
    log "되돌린 서버도 정상이 아니다. 직접 확인이 필요하다."
  fi
fi
exit 1
