#!/bin/bash
# 앱 실행 계정(DB_APP_USERNAME)을 만든다. 테이블 소유자(APP_USER = DB_OWNER_USERNAME)는 gvenzl 이미지가 따로 만든다.
#
# 소유자와 앱 계정을 나누는 이유: 테이블 소유자는 자기 테이블에 무엇이든 할 수 있다(트리거 끄기, TRUNCATE, DROP).
# 앱 계정은 접속 권한(CREATE SESSION)만 갖고 시작하고, 테이블 권한은 소유자가 Flyway(R__app_user_grants.sql)로
# 필요한 만큼만 준다. 그래서 앱 계정이 탈취되어도 원장·감사 기록을 지우거나 트리거를 끌 수 없다.
#
# 언제 실행되나:
# - compose.yaml: 데이터 볼륨이 비어 있어 DB 를 처음 만들 때 한 번(/container-entrypoint-initdb.d).
#   이미 있는 볼륨에는 직접 실행한다: docker compose exec oracle bash /container-entrypoint-initdb.d/01-create-app-user.sh
# - 테스트(TestcontainersConfiguration): 컨테이너를 띄울 때마다.
# 이미 계정이 있으면 아무것도 하지 않으므로 여러 번 실행해도 된다.
set -euo pipefail

: "${DB_APP_USERNAME:?DB_APP_USERNAME 이 필요합니다}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 가 필요합니다}"

# 이름은 SQL 식별자로, 비밀번호는 따옴표 안 문자열로 들어간다. 엉뚱한 SQL 이 끼어들지 않게 글자를 제한한다.
if [[ ! "$DB_APP_USERNAME" =~ ^[A-Za-z][A-Za-z0-9_]{0,29}$ ]]; then
  echo "DB_APP_USERNAME 은 영문으로 시작하는 영문·숫자·_ 30자 이내여야 합니다" >&2
  exit 1
fi
if [[ "$DB_APP_PASSWORD" == *'"'* ]]; then
  echo "DB_APP_PASSWORD 에 큰따옴표(\")는 쓸 수 없습니다" >&2
  exit 1
fi

sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM dba_users WHERE username = UPPER('${DB_APP_USERNAME}');
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE USER ${DB_APP_USERNAME} IDENTIFIED BY "${DB_APP_PASSWORD}"';
        -- 접속만 할 수 있다. 테이블·시퀀스를 만드는 권한(CREATE TABLE 등)도, 저장 공간(QUOTA)도 주지 않는다.
        EXECUTE IMMEDIATE 'GRANT CREATE SESSION TO ${DB_APP_USERNAME}';
    END IF;
END;
/
EXIT
SQL
echo "앱 계정 ${DB_APP_USERNAME} 준비 완료"
