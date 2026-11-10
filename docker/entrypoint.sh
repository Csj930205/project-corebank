#!/bin/sh
# 컨테이너 시작 명령. 첫 인자로 무엇을 실행할지 고른다. JAVA_OPTS 로 JVM 옵션(메모리 등)을 넘길 수 있다.
#   server  (기본) 서버. 시작할 때 DB 버전이 코드와 같은지 확인만 하고, 다르면 뜨지 않는다.
#   migrate DB 마이그레이션(MigrationApplication). 끝나면 컨테이너도 끝난다. 실패하면 종료 코드 1.
set -eu

case "${1:-server}" in
  server)
    exec java ${JAVA_OPTS:-} -jar /app/app.jar
    ;;
  migrate)
    exec java ${JAVA_OPTS:-} -cp /app/app.jar \
      -Dloader.main=com.corebank.migration.MigrationApplication \
      org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  *)
    exec "$@"
    ;;
esac
