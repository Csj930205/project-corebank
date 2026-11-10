# project-corebank: 미니 코어뱅킹 서버

[![CI](https://github.com/Csj930205/project-corebank/actions/workflows/ci.yml/badge.svg)](https://github.com/Csj930205/project-corebank/actions/workflows/ci.yml)

계좌 개설, 입출금, 이체, 해지와 일 마감 대사 배치를 처리하고, 감사 로그·원장 불변성·로그인 잠금 같은 보안 장치를 갖춘 코어뱅킹 서버다. 고객은 본인 계좌만, 관리자(창구 직원)는 모든 계좌를 다룬다. 금융권(은행·카드·증권) 지원용 포트폴리오로 만들고 있다.

기능 수보다 **"어떤 상황에서도 돈이 틀어지지 않는다"** 를 증명하는 데 집중한다. 모든 거래를 복식부기 원장에 남기고, 동시 요청과 재전송에도 잔액이 맞는지 실제 Oracle 위에서 테스트한다.

- 화면: 별도 프로젝트 [`project-corebank-front`](https://github.com/Csj930205/project-corebank-front) (React + TypeScript)
- 처음 기획과 후보 비교: [`docs/기획_2026-11-02.md`](docs/기획_2026-11-02.md)

## 진행 상황

| 기능 | 상태 | 내용 |
|---|---|---|
| 계좌 개설·조회 | 완료 | 계좌번호 = 은행 코드 3자리 + 시퀀스 10자리 |
| 입금·출금 | 완료 | 비관적 락, 잔액 부족 거부, 원장 기록 |
| 이체 | 완료 | 출금과 입금을 한 트랜잭션으로 처리, 데드락 방지 잠금 순서 |
| 멱등성 키 | 완료 | 입금·출금·이체·해지에 필수, 재전송은 처음 결과 그대로 |
| 거래내역 | 완료 | 커서 방식 페이지네이션 |
| 계좌 해지 | 완료 | 남은 잔액 지급 후 CLOSED, 해지 후 거래 거부 |
| 관리자 로그인·조회 | 완료 | JWT, 계좌 검색, 전체 요약 |
| 일 마감 대사 배치 | 완료 | Spring Batch. 매일 00:10 자동 + 관리자 수동 실행, 불일치 5종 탐지·기록, 일계표 |
| 마감 결과 화면 | 완료 | 관리자 화면에서 마감 실행, 일계표·불일치 내역 확인, 다시 마감 |
| 고객 인증 | 완료 | 가입·로그인(JWT), 고객은 본인 계좌만·관리자는 전체, 남의 계좌는 404 로 숨김 |
| 고객 화면 | 완료 | 로그인 하나로 관리자·고객 구분, 내 계좌, 수취인 확인 후 이체 |
| CI | 완료 | GitHub Actions. push·PR 마다 전체 테스트를 Linux 와 실제 Oracle 에서 실행 |
| 감사 로그 | 완료 | API 요청마다 누가·무엇을·어느 계좌에·결과 기록. 실패·거부된 시도와 조회도 남김, DB 트리거로 수정·삭제 차단 |
| 원장 불변성 | 완료 | 원장 거래·분개·이체 기록의 수정·삭제를 DB 트리거가 거부. 정정은 역분개로 |
| 횟수 제한 | 완료 | 로그인 5번 연속 실패 시 15분 잠금(관리자 해제 가능), 수취인 조회 고객당 시간당 30번 |
| DB 계정 분리 | 완료 | 테이블 소유자(마이그레이션 전용)와 앱 계정 분리. 앱 계정은 필요한 테이블 권한만, DDL 불가 |
| 마이그레이션 분리 | 완료 | 마이그레이션은 별도 프로그램(`./gradlew migrate`)이 소유자로 실행. 서버는 DB 버전 확인만 하고 소유자 비밀번호가 필요 없다 |

## 기술 스택

| 영역 | 선택 |
|---|---|
| 언어·프레임워크 | Java 25, Spring Boot 4.1.2, Spring Security (OAuth2 Resource Server), Spring Batch 6 |
| DB | Oracle Database Free 23 (Docker), Flyway 마이그레이션 |
| 데이터 접근 | Spring Data JPA, 집계는 네이티브 SQL |
| 테스트·CI | JUnit 5, Testcontainers(실제 Oracle), MockMvc, Postman CLI, GitHub Actions |
| 화면 | React, TypeScript, Vite, TanStack Query (별도 프로젝트) |

## 실행 방법

필요한 것: JDK 25, Docker (macOS 는 Colima 로 확인했다).

```bash
# 1. 설정 파일을 만들고 비밀번호와 JWT_SECRET 을 바꾼다
cp .env.example .env

# 2. Oracle 실행 (처음에는 이미지 다운로드와 초기화로 몇 분 걸린다). 처음 만들 때 테이블 소유자와 앱 계정을 함께 만든다
docker compose up -d --wait

# 3. 테이블 만들기·변경 (테이블 소유자 계정). 마이그레이션 파일이 바뀔 때마다 서버보다 먼저 실행한다
./gradlew migrate

# 4. 서버 실행 (http://localhost:8080). .env 는 서버가 직접 읽는다.
#    서버는 마이그레이션하지 않고, DB 가 코드와 같은 버전인지 확인만 한다. 아니면 시작하지 않는다
./gradlew bootRun
```

macOS 에서 Colima 를 쓰면 재부팅 뒤 `colima start` 를 먼저 실행한다.

계정 분리 전에 만든 DB 볼륨을 쓰고 있다면 `.env` 의 `DB_USERNAME`/`DB_PASSWORD` 를 `DB_OWNER_USERNAME`/`DB_OWNER_PASSWORD` 로 바꾸고 `DB_APP_USERNAME`/`DB_APP_PASSWORD` 를 추가한 뒤, 앱 계정을 한 번 만들고 `./gradlew migrate` 를 실행한다(앱 권한은 마이그레이션이 준다).

```bash
docker compose up -d --wait
docker compose exec oracle bash /container-entrypoint-initdb.d/01-create-app-user.sh
```

화면은 `project-corebank-front` 폴더에서 `npm install && npm run dev` 로 실행한다. 개발 서버가 `/api` 요청을 8080 으로 넘기므로 CORS 설정이 필요 없다. 화면의 로그인은 하나다. `.env` 의 관리자 계정으로 들어가면 운영 콘솔, 가입한 고객 계정으로 들어가면 내 계좌 화면이 열린다.

### 처음 써 보기

서버를 띄운 뒤 터미널에서 바로 해 볼 수 있다(`jq` 필요). 같은 아이디로 다시 가입하면 409 이므로, 두 번째부터는 아이디를 바꾸거나 `/auth/login` 으로 로그인한다.

```bash
# 고객으로 가입하면 바로 토큰을 준다
TOKEN=$(curl -s -X POST localhost:8080/auth/customer/signup -H 'Content-Type: application/json' \
  -d '{"username":"hong","password":"customer-password-1","name":"홍길동"}' | jq -r .accessToken)

# 본인 명의 계좌 개설
curl -s -X POST localhost:8080/accounts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{}'

# 입금 (돈이 움직이는 요청은 Idempotency-Key 필수)
curl -s -X POST localhost:8080/accounts/{계좌 id}/deposits -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"amount":10000}'

# 내 계좌 목록
curl -s localhost:8080/accounts -H "Authorization: Bearer $TOKEN"
```

관리자 토큰도 같은 `POST /auth/login` 에 `.env` 의 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 를 보내 받는다. 응답의 `role` 이 `ADMIN` 이면 관리자, `CUSTOMER` 면 고객 토큰이다.

| `.env` 항목 | 설명 |
|---|---|
| `ORACLE_PASSWORD` | Oracle 관리자 비밀번호 (컨테이너용) |
| `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD` | 테이블 소유자. 마이그레이션만 쓴다(테이블 생성, 앱 계정에 권한 부여). 서버는 이름만 스키마 이름으로 쓰고 비밀번호는 읽지 않는다 |
| `DB_APP_USERNAME`, `DB_APP_PASSWORD` | 서버가 쓰는 앱 계정. 소유자가 준 권한만 있다 |
| `DB_URL` | (선택) DB 접속 주소. 기본값은 compose 의 `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `JWT_SECRET` | JWT 서명 키, 32바이트 이상 (`openssl rand -base64 48`) |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | 첫 관리자 계정. 서버가 처음 시작할 때 한 번만 만든다 (비밀번호 12자 이상) |

횟수 제한(로그인 잠금 횟수·시간, 수취인 조회 한도)과 자동 마감 시각은 `src/main/resources/application.properties` 에 기본값이 있다.

## API

로그인과 가입을 뺀 모든 API 는 `Authorization: Bearer <토큰>` 이 필요하다. 계좌 API 는 **고객은 본인 계좌만, 관리자는 모든 계좌**를 다룬다(관리자 = 창구 직원 역할).

돈이 움직이는 요청(★)은 `Idempotency-Key` 헤더가 필수다. 새 요청마다 새 UUID 를 만들고, 응답을 못 받아 다시 보낼 때만 같은 키를 쓴다.

| 메서드 | 경로 | 설명 | 성공 |
|---|---|---|---|
| POST | `/auth/login` | 로그인 `{username, password}`. 관리자 계정이면 관리자 토큰, 아니면 고객 토큰 (30분, 응답 `role` 로 구분). 5번 연속 실패하면 15분 잠금(429) | 200 |
| POST | `/auth/customer/signup` | 고객 가입 `{username, password, name}` → 바로 고객 토큰 | 201 |
| POST | `/auth/customer/login` | 고객으로만 로그인 (관리자 계정은 401) | 200 |
| GET | `/accounts` | 내 계좌 목록 (고객 전용) | 200 |
| POST | `/accounts` | 계좌 개설. 고객은 본인 명의, 관리자는 `{ownerName}` 필수 | 201 + `Location` |
| GET | `/accounts/{id}` | 계좌 조회 | 200 |
| POST | `/accounts/{id}/deposits` ★ | 입금 `{amount}` | 201 |
| POST | `/accounts/{id}/withdrawals` ★ | 출금 `{amount}` | 201 |
| GET | `/accounts/lookup?accountNumber=` | 수취인 조회. 받는 계좌가 있고 받을 수 있는지와 가린 예금주 이름(`홍*동`). 없으면 404, 해지 계좌면 409. 고객은 시간당 30번(429) | 200 |
| POST | `/transfers` ★ | 이체 `{fromAccountId, toAccountId 또는 toAccountNumber, amount}`. 고객은 본인 계좌에서만 보낸다. 받는 계좌 잔액(`toBalanceAfter`)은 관리자에게만 준다 | 201 |
| POST | `/accounts/{id}/closure` ★ | 해지. 남은 잔액을 지급하고 CLOSED 로 바꾼다 | 200 |
| GET | `/accounts/{id}/transactions?size=20&before=` | 거래내역 (최신순, 다음 페이지는 응답의 `nextBefore`) | 200 |
| GET | `/auth/me` | 현재 토큰 정보 (인증 필요) | 200 |
| GET | `/admin/accounts?query=&status=&openedFrom=&openedTo=&minBalance=&maxBalance=&page=&size=` | 계좌 검색 (관리자). 검색어는 계좌번호 앞자리 또는 이름 일부. 개설일(`YYYY-MM-DD`)·잔액(원) 범위는 양 끝 포함, 시작이 끝보다 크면 빈 목록 | 200 |
| GET | `/admin/summary` | 계좌·잔액·거래 수 요약과 원장 점검 결과 (관리자) | 200 |
| GET | `/admin/login-locks` | 지금 잠긴 아이디(실패 수, 풀리는 시각), 곧 풀리는 순서. 가입하지 않은 아이디도 나온다 (관리자) | 200 |
| POST | `/admin/login-locks/{username}/unlock` | 로그인 잠금 해제. 창구에서 본인 확인 뒤 잠금 시간을 기다리지 않고 푼다 (관리자) | 204 |
| POST | `/admin/closings` | 일 마감 수동 실행 `{businessDate}`. 끝날 때까지 기다렸다가 결과 반환 (관리자) | 201 + `Location` |
| GET | `/admin/closings?page=&size=` | 마감 실행 목록, 최신순 (관리자) | 200 |
| GET | `/admin/closings/{id}` | 마감 상세: 일계표, 불일치 내역 (관리자) | 200 |
| GET | `/admin/audit-logs?actor=&action=&outcome=&accountId=&from=&to=&page=&size=` | 감사 기록, 최신순. 날짜는 `YYYY-MM-DD`, 양 끝 포함 (관리자) | 200 |

금액은 원 단위 정수다. `1.5` 같은 소수나 문자열은 잘라 받지 않고 거부한다.

감사 대상 요청의 응답에는 `X-Request-Id` 헤더가 붙는다. 감사 기록의 `requestId` 와 같은 값이라, 문의가 오면 이 번호로 그 요청의 기록을 바로 찾는다.

### 에러 응답

모든 에러는 `{ "code": "...", "message": "..." }` 형식이다. 스프링이 컨트롤러 전에 내는 404·405·415 도 같은 형식으로 바꿨다.

| 상태 | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 검증 실패, 깨진 JSON, 잘못된 경로·쿼리 값 |
| 400 | `IDEMPOTENCY_KEY_MISSING`, `INVALID_IDEMPOTENCY_KEY` | 키가 없거나 형식(`[A-Za-z0-9_-]{1,100}`)이 틀림 |
| 400 | `SAME_ACCOUNT_TRANSFER` | 같은 계좌로 이체 |
| 401 | `INVALID_CREDENTIALS` | 로그인 실패 |
| 401 | `UNAUTHORIZED` | 토큰이 없거나 만료·위조 |
| 403 | `FORBIDDEN` | 권한 없음 (고객 토큰으로 관리자 API 등) |
| 404 | `ACCOUNT_NOT_FOUND`, `CLOSING_NOT_FOUND`, `RESOURCE_NOT_FOUND` | 없는(또는 남의) 계좌, 없는 마감 실행, 없는 경로 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 메서드 (`Allow` 헤더 포함) |
| 409 | `ACCOUNT_CLOSED` | 해지된 계좌에 거래 또는 재해지 |
| 409 | `CLOSING_ALREADY_RUNNING` | 같은 영업일 마감이 실행 중 |
| 409 | `USERNAME_TAKEN` | 이미 쓰는 아이디로 가입 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JSON 이 아닌 요청 본문 |
| 422 | `INSUFFICIENT_BALANCE` | 잔액 부족 |
| 422 | `BALANCE_LIMIT_EXCEEDED` | 잔액이 long 범위를 넘음 |
| 422 | `IDEMPOTENCY_KEY_REUSED` | 이미 다른 요청에 쓴 키 |
| 422 | `BUSINESS_DATE_NOT_ENDED` | 오늘이나 미래 날짜를 마감 |
| 429 | `LOGIN_LOCKED` | 로그인 실패가 쌓여 잠긴 아이디 (`Retry-After` 헤더: 풀릴 때까지 남은 초) |
| 429 | `RATE_LIMITED` | 수취인 조회 한도 초과 (`Retry-After`: 이번 구간이 끝날 때까지 남은 초) |

## 코드 구조

기능별로 패키지를 나눴다. 각 패키지 안에 컨트롤러(HTTP), 서비스(트랜잭션·업무 규칙), 엔티티·저장소가 함께 있다.

```
com.corebank
  account      계좌 개설·조회·입출금·해지·거래내역, 계좌 접근 권한(AccountAccessPolicy)
  transfer     계좌 간 이체
  ledger       복식부기 원장 기록과 조회 (호출자 트랜잭션 안에서만 실행)
  idempotency  멱등성 키 확인·기록
  closing      일 마감 대사 배치(Spring Batch 잡), 마감 결과 조회 API, 자동 마감 스케줄러
  customer     고객 계정(가입, 로그인 확인)
  auth         JWT 발급·검증, 보안 규칙(SecurityConfig), 로그인 API·잠금(LoginThrottle), 요청한 사람(Actor)
  admin        관리자 계정, 관리자 조회 API(계좌 검색, 요약)
  audit        감사 로그(요청마다 기록하는 필터, 조회 API)
  ratelimit    고정 구간 횟수 제한(RateLimiter), 지난 기록 정리
  common       공통 에러 응답(ApiExceptionHandler), 시계(Clock) 빈, DB 정밀도 현재 시각(DbTime)
```

## 설계에서 신경 쓴 점

### 복식부기 원장

잔액을 바꾸는 모든 거래는 `ledger_transaction`(거래 한 건)과 `ledger_entry`(차변·대변 분개)에 남는다. 기록은 추가만 한다. 잘못된 거래는 고치지 않고 반대 거래(역분개)를 새로 추가해 바로잡는 것이 원장의 규칙이라, 과거 기록은 언제나 그때 그대로다.

| 거래 | 차변 | 대변 |
|---|---|---|
| 입금 | 현금 | 고객 예금 |
| 출금, 해지 지급 | 고객 예금 | 현금 |
| 이체 (보내는 쪽) | 고객 예금(보내는 계좌) | 이체 가계정 |
| 이체 (받는 쪽) | 이체 가계정 | 고객 예금(받는 계좌) |

이체는 계좌마다 거래를 하나씩 남겨, 각 계좌의 거래내역에 거래 후 잔액이 보이게 했다. 두 거래를 잇는 이체 가계정은 이체가 끝나면 합계가 항상 0이다. 0이 아니면 반쪽만 기록된 이체가 있다는 뜻이다.

### 지키는 불변 조건

1. 전체 원장의 차변 합과 대변 합은 같다.
2. 계좌 잔액은 그 계좌의 고객 예금 분개 합계와 같다.
3. 잔액은 음수가 되지 않는다 (코드 검사 + DB `CHECK` 제약).
4. 같은 멱등성 키의 요청은 한 번만 반영된다 (서비스 확인 + DB 유니크 제약).
5. 해지된 계좌는 잔액이 0이고, 해지 지급은 계좌당 한 건이다 (DB 제약).
6. 원장 거래·분개·이체 기록은 한 번 쓰면 바뀌지 않는다 (DB 트리거, V12).

동시성 테스트는 요청이 끝난 뒤 1, 2번을 SQL 로 직접 확인한다. 관리자 요약 API 도 조회할 때마다 1, 2번과 이체 가계정 잔액을 점검해 보여 준다.

### 원장 불변성: 막기와 찾기 두 겹

- **막기:** `ledger_transaction`, `ledger_entry`, `transfer` 에 UPDATE·DELETE 를 하면 두 겹에서 거부된다. 앱 계정에는 그 권한이 없고(ORA-41900), 권한이 있는 테이블 소유자도 DB 트리거가 막는다(ORA-20002). 코드에 버그가 있거나 누가 앱 계정으로 SQL 을 직접 실행해도 돈의 흔적은 바뀌지 않는다. 코드가 지키는 규칙("원장은 고치지 않는다")을 DB 도 지키게 한 것이다.
- **찾기:** 테이블 소유자는 트리거를 끌 수 있다. 그렇게 우회한 변경은 일 마감 대사가 매일 찾아낸다. 그래서 대사 테스트는 소유자 계정으로 트리거를 잠시 끄고 원장을 틀어 놓은 뒤 배치가 찾아내는지 본다(`LedgerTampering`). 앱 코드에는 트리거를 우회하는 길을 두지 않았다.
- 계좌 잔액(`account.balance`)은 거래마다 바뀌는 값이라 막지 않는다. 원장 합계와 같은지는 대사가 확인한다.

### DB 계정 분리: 테이블 소유자와 앱 계정

테이블 소유자는 자기 테이블에 무엇이든 할 수 있다(트리거 끄기, TRUNCATE, DROP). 그래서 서버는 소유자가 아닌 앱 계정으로 접속한다.

| 계정 | 누가 쓰나 | 할 수 있는 일 |
|---|---|---|
| 테이블 소유자 (`DB_OWNER_*`) | 마이그레이션 프로그램 (`MigrationApplication`) | 테이블·트리거 생성과 변경, 앱 계정에 권한 부여 |
| 앱 계정 (`DB_APP_*`) | 서버 (평소 요청 처리) | 접속(CREATE SESSION)과 소유자가 준 테이블 권한만. 자기 스키마에 객체를 만들 수도 없다 |

- **마이그레이션 분리:** 서버는 Flyway 마이그레이션을 하지 않는다. 시작할 때 앱 계정으로 소유자 스키마의 이력(`flyway_schema_history`, 읽기 권한만)을 읽어 코드와 같은 버전인지 확인(validate)만 하고, 적용 안 된 파일이 있으면 시작하지 않는다(`SchemaVersionCheck`). 마이그레이션은 같은 코드의 다른 실행 진입점 `MigrationApplication` 이 소유자로 한다. 웹·JPA·보안·배치 없이 DataSource 와 Flyway 만 켜고, 설정은 `migration.properties` 를 읽는다. 그래서 소유자 비밀번호는 마이그레이션을 실행하는 곳에만 있으면 된다.
- **배포 순서:** 같은 jar 로 마이그레이션 → 서버 시작.

  ```bash
  java -cp app.jar -Dloader.main=com.corebank.migration.MigrationApplication org.springframework.boot.loader.launch.PropertiesLauncher   # 소유자 비밀번호 필요
  java -jar app.jar                                                                                                                    # 앱 계정만 필요
  ```

- **권한 목록 한 곳:** `R__app_user_grants.sql`(반복 실행 마이그레이션)이 앱 계정 권한의 전체 목록이다. 파일이 바뀌면 Flyway 가 앱 계정 권한을 모두 거두고 목록대로 다시 준다. 새 테이블을 만들고 여기에 빠뜨리면 테스트가 깨진다.
- **최소 권한:** 원장·이체·감사 기록·멱등성 키·고객·관리자는 SELECT·INSERT 만 준다. 계좌는 바뀌는 열(잔액, 상태, 해지 시각)만 UPDATE 할 수 있어 계좌번호·이름·개설일은 앱도 못 바꾼다. DELETE 는 횟수 제한 기록(`login_attempt`, `rate_limit`)에만 준다.
- **스키마:** 앱 계정은 테이블이 없으므로 접속할 때마다 `ALTER SESSION SET CURRENT_SCHEMA` 로 소유자 스키마를 기본으로 정한다(Hikari `connection-init-sql`). SQL 에 스키마 이름을 붙이지 않아도 된다.
- **계정 만들기:** 앱 계정은 DBA 만 만들 수 있어 `docker/oracle-init/01-create-app-user.sh` 가 DB 초기화 때 만든다. 테스트 컨테이너도 같은 스크립트와 같은 계정 구성으로 뜨고, 배포처럼 `MigrationApplication` 으로 먼저 마이그레이션한 뒤 서버를 앱 계정으로 띄운다. 그래서 모든 통합 테스트가 앱 계정 권한만으로 돌아간다.

### 동시성: 비관적 락

입금·출금·이체·해지는 계좌를 `SELECT ... FOR UPDATE` 로 잠근 뒤 처리한다. 잠금이 없으면 잔액 10,000원 계좌에 7,000원 출금 두 건이 동시에 들어올 때 둘 다 잔액 확인을 통과한다.

이체는 두 계좌를 **id 가 작은 계좌부터** 잠근다. A→B 와 B→A 이체가 동시에 와도 같은 순서로 잠그므로 서로 기다리는 데드락이 생기지 않는다.

### 멱등성 키

네트워크가 끊겨 응답을 못 받은 클라이언트가 다시 보내도 돈은 한 번만 움직여야 한다.

- 성공한 요청은 키, 요청 요약(fingerprint), 결과 id 를 업무 처리와 **같은 트랜잭션**에 저장한다. 업무가 롤백되면 키 기록도 사라지므로, 잔액 부족으로 실패한 키는 다시 쓸 수 있다.
- 키 확인은 **계좌를 잠근 뒤**에 한다. 같은 요청 두 개가 동시에 오면 두 번째는 잠금에서 기다렸다가 첫 번째가 저장한 결과를 받는다.
- 재전송에는 처음과 같은 상태 코드와 본문을 주고, `Idempotent-Replayed: true` 헤더로만 구분한다.
- 같은 키로 금액이나 계좌가 다른 요청이 오면 422 로 거부한다.

### 계좌 해지

- 행을 지우지 않고 상태를 CLOSED 로 바꾼다. 해지 뒤에도 계좌와 거래내역은 조회된다. 그래서 `DELETE` 대신 `POST /accounts/{id}/closure` 를 쓴다.
- 남은 잔액은 `CLOSURE_PAYOUT` 거래로 고객에게 지급(현금)하고 잔액은 0이 된다. 잔액이 0이면 원장 기록 없이 상태만 바뀐다.
- 해지와 입금이 동시에 오면 잠금으로 줄을 세운다. 입금이 먼저면 그 돈까지 지급되고, 해지가 먼저면 입금이 거부된다. 어느 쪽이든 돈이 사라지지 않는다.

### 일 마감 대사 배치

매일 00:10 에 전날 영업일(서버 시각 0시~24시)을 자동 마감한다. 관리자가 날짜를 골라 수동으로 실행할 수도 있다. 0시 정각이 아닌 이유는 자정 직전에 시작된 거래가 커밋될 시간을 두기 위해서다.

Spring Batch 잡 하나가 네 단계를 순서대로 실행한다.

| 단계 | 방식 | 하는 일 |
|---|---|---|
| `dailyTotalsStep` | tasklet | 일계표: 영업일 거래를 종류별로 건수·금액 집계 |
| `accountReconcileStep` | chunk (100) | 계좌를 100개씩 읽어 잔액과 원장을 비교. 불일치만 기록 |
| `ledgerCheckStep` | tasklet | 원장 전체 점검: 거래별 차대 일치, 반쪽 이체, 이체 가계정 |
| `finishStep` | tasklet | 계좌 수, 예금 잔액 합계, 불일치 건수와 결과 상태 기록 |

찾아내는 불일치는 다섯 가지다.

| 종류 | 의미 |
|---|---|
| `BALANCE_LEDGER_MISMATCH` | 계좌의 현재 잔액이 원장 합계와 다르다 (잔액 컬럼이 원장 없이 바뀜) |
| `SNAPSHOT_MISMATCH` | 마감 시점 마지막 거래의 거래 후 잔액이 그 시점 원장 합계와 다르다 |
| `UNBALANCED_TRANSACTION` | 거래 한 건의 차변 합, 대변 합, 거래 금액이 같지 않다 |
| `INCOMPLETE_TRANSFER` | 이체 한 건의 원장 거래가 2건(출금 쪽, 입금 쪽)이 아니다 |
| `CLEARING_NOT_ZERO` | 마감 시점 이체 가계정 잔액이 0이 아니다 |

- **계좌 대사만 청크 방식인 이유:** 계좌 수만큼 커지는 유일한 단계라 한 번에 다 읽지 않는다. 리더는 OFFSET 이 아니라 "마지막 id 다음부터" 읽는다. 불일치가 없는 계좌는 프로세서가 걸러 내므로, 실행 이력의 `FILTER_COUNT` 가 곧 정상 계좌 수다. 나머지 단계는 SQL 한 문장으로 DB 안에서 끝난다.
- **과거 날짜도 그날 기준으로 검사한다:** 계좌가 그 시점에 원장과 맞았는지는 `ledger_transaction.balance_after` 로 다시 확인할 수 있다.
- **결과와 실행 이력을 나눠 남긴다:** `daily_closing`·`daily_closing_total`·`closing_mismatch` 는 "돈이 맞았는가", Spring Batch 의 `BATCH_*` 테이블은 "배치가 끝까지 돌았는가" 를 기록한다. 결과는 `BALANCED`(불일치 없음), `UNBALANCED`(불일치 있음), `FAILED`(배치 실패) 중 하나다.
- **재실행:** 불일치를 고친 뒤 같은 날을 다시 마감할 수 있고 이전 실행도 남는다. 같은 날짜는 동시에 하나만 실행된다(RUNNING 행에만 걸리는 함수 기반 유니크 인덱스). 실행 중 서버가 꺼져 1시간 넘게 RUNNING 으로 남은 실행은 다음 실행 때 FAILED 로 정리된다.
- **오늘과 미래는 마감할 수 없다:** 그날 거래가 더 생길 수 있어 결과가 확정되지 않는다.

### 거래내역: 커서 페이지네이션

`OFFSET` 대신 마지막으로 본 거래 id(`before`)부터 읽는다. 새 거래가 계속 쌓여도 페이지가 밀리지 않고, `(account_id, id)` 인덱스를 그대로 탄다. 다음 페이지가 있는지는 `size + 1` 건을 읽어 판단하므로 `COUNT` 쿼리가 없다.

### 인증과 권한

- **토큰:** JWT(HS256, 30분). 서버가 토큰을 저장하지 않는다(STATELESS). 토큰의 `scope` 가 `ADMIN` 이면 관리자, `CUSTOMER` 면 고객이고, 고객 토큰에는 고객 번호(`cid`)가 들어 있다. 서명 때문에 고객이 `cid` 를 바꿀 수 없다.
- **두 종류의 계정, 로그인은 하나:** 관리자(`admin_user`)와 고객(`customer`)은 테이블이 다르지만 로그인 주소는 `/auth/login` 하나다. 아이디가 관리자 계정이면 관리자로, 아니면 고객으로 확인하고 응답 `role` 로 알려 준다. 그래서 고객 가입은 관리자 아이디를 쓸 수 없다(409). 어느 경로든 비밀번호 비교는 한 번이라 응답 시간으로 관리자 아이디를 알아낼 수 없다. 비밀번호는 둘 다 BCrypt 해시로만 저장하고, 로그인 실패 시 아이디와 비밀번호 중 무엇이 틀렸는지 알려 주지 않는다. 없는 아이디도 비밀번호 비교를 한 번 해서 응답 시간으로 아이디 존재 여부를 알 수 없게 했다.
- **URL 규칙 + 계좌 소유 확인:** URL 규칙(SecurityConfig)은 "로그인했는가, 관리자인가" 만 본다. "이 계좌가 본인 것인가" 는 요청 내용을 봐야 알 수 있어, 컨트롤러가 업무 처리 전에 `AccountAccessPolicy` 로 확인한다. 이체는 보내는 계좌만 본인 것이면 된다.
- **남의 계좌는 403 이 아니라 404:** 403 이면 "그 번호의 계좌가 있다" 는 사실이 드러나, id 를 바꿔 가며 남의 계좌를 찾아낼 수 있다. 관리자가 이름만으로 연(주인 없는) 계좌도 고객에게는 남의 계좌다.
- **가입:** 아이디는 영문 소문자로 시작하는 4~30자, 비밀번호는 10자 이상(복잡도 규칙보다 길이를 본다). 아이디 중복은 DB 유니크 제약으로 확인해, 동시에 같은 아이디로 가입해도 하나만 통과한다. 고객이 계좌를 열면 예금주는 항상 본인 이름이다.
- **수취인 조회:** 받는 계좌는 보통 남의 계좌라 본인 확인 없이 조회되지만(고객은 시간당 30번, 아래 횟수 제한), 예금주 이름은 첫·끝 글자만 남기고 가리고(홍길동 → 홍*동) id·잔액은 주지 않는다. 같은 이유로 이체 응답의 받는 계좌 잔액도 고객에게는 null 이다. 고객 화면은 남의 계좌 id 를 모르므로 이체 요청에 계좌번호(`toAccountNumber`)를 쓴다.

### 횟수 제한

**로그인 잠금.** 같은 아이디로 5번 연속 실패하면 15분 동안 잠근다(429 `LOGIN_LOCKED`). 잠긴 동안은 맞는 비밀번호도 거부하고, 성공하면 처음부터 센다. 창구에서 본인 확인을 했으면 관리자가 잠긴 목록에서 골라 바로 풀 수 있다(화면: 관리자 → 로그인 잠금).

- **비밀번호 확인 전에 센다.** 확인 뒤에 세면 같은 아이디로 동시에 100개를 보낼 때 모두 "아직 0번" 을 보고 통과해 100번을 시도할 수 있다. 그래서 시도마다 `login_attempt` 행을 잠그고(SELECT FOR UPDATE) 1을 더해 커밋한 뒤에 비밀번호를 확인하고, 성공하면 기록을 지운다. 동시에 10개를 보내는 테스트에서 비밀번호 확인은 정확히 한도만큼만 일어난다. 행 잠금은 1을 더하는 짧은 순간에만 잡고, 시간이 걸리는 BCrypt 확인 동안에는 잡지 않는다.
- **횟수는 롤백되지 않는다.** 로그인 실패는 예외로 끝나 트랜잭션이 롤백된다. 횟수 기록은 따로(`REQUIRES_NEW`) 커밋한다.
- **없는 아이디도 똑같이 잠근다.** 있는 아이디만 잠기면 "잠기는가" 로 계정이 있는지 알아낼 수 있다. 응답도 같다.

**수취인 조회.** 고객마다 1시간 구간(정시~정시)에 30번까지다(429 `RATE_LIMITED`). 없는 계좌번호(404)도 한 번으로 센다. 그래야 있는 번호를 찾아 헤매는 시도도 묶인다. 관리자(창구)는 업무상 조회가 많아 묶지 않고 대신 감사 로그에 모두 남는다.

두 기록 모두 서버 메모리가 아니라 DB(`login_attempt`, `rate_limit`)에 센다. 서버를 여러 대 띄워도 같은 기준으로 막는다. 막힌 요청은 감사 로그에 거부(DENIED)로 남고, 429 응답의 `Retry-After` 헤더가 언제 다시 할 수 있는지 알려 준다.

### 감사 로그

"누가, 언제, 무엇을, 어느 계좌에 했고 결과는 어땠나" 를 API 요청마다 한 줄씩 `audit_log` 에 남긴다. 돈이 움직인 요청뿐 아니라 **조회**(잔액, 거래내역, 수취인 조회, 관리자 목록)와 **실패·거부된 시도**(로그인 실패, 401/403, 횟수 제한 429, 잔액 부족)도 남긴다. 남의 정보를 본 기록과 막힌 시도가 사고 조사에서 가장 먼저 찾는 기록이기 때문이다. 감사 기록을 조회한 것도 다시 기록된다.

- **한곳에서 기록한다:** 컨트롤러마다 로그 코드를 넣지 않고, 보안 필터 체인 안의 필터(`AuditLogFilter`) 하나가 요청이 끝날 때 기록한다. 무엇을 남길지는 `AuditAction` 의 메서드 + 경로 패턴 목록이 정한다. 새 API 는 이 목록에 한 줄을 더하면 되고, 컨트롤러는 손대지 않는다(목록에 없는 요청은 기록하지 않는다).
- **필터 위치:** 토큰 확인(`BearerTokenAuthenticationFilter`) 바로 앞이다. 그래서 무효한 토큰으로 401 을 받은 요청도 남고, 요청이 끝난 시점에도 누가 요청했는지(SecurityContext) 알 수 있다. 컨트롤러까지 가지 않은 요청도 남기려고 스프링 매핑 정보 대신 경로를 직접 맞춘다.
- **업무가 롤백돼도 남는다:** 업무 트랜잭션이 끝난 뒤 별도로 쓰므로, 잔액 부족으로 롤백된 출금도 에러 코드(`INSUFFICIENT_BALANCE`)와 함께 남는다.
- **본문은 남기지 않는다:** 경로와 쿼리 문자열만 남긴다. 비밀번호가 기록될 일이 없다. 로그인 아이디나 이체 계좌처럼 본문에만 있는 값은 컨트롤러가 업무 처리 **전에** `AuditContext` 에 적어, 실패한 요청에도 남는다. 금액은 원장이 기준이라 남기지 않는다.
- **위변조 방지:** `audit_log` 에 UPDATE·DELETE 를 하면 DB 트리거가 거부한다(ORA-20001). 앱 계정이 탈취되어도 흔적을 지우지 못한다.

### 시각과 날짜

거래 시각(`created_at`)은 앱 서버 시각(시스템 시간대, 한국 시각)으로 저장하고, "오늘", "영업일" 도 같은 기준으로 계산한다. 이를 위해 현재 시각을 `Clock` 빈 하나로 두고 모든 코드가 그것을 쓴다(테스트에서는 날짜를 바꿔 끼운다). DB 의 `SYSDATE` 는 쓰지 않는다. Oracle 컨테이너는 UTC 라, 한국 시각 0~9시에는 DB 날짜가 하루 늦기 때문이다.

엔티티가 저장할 시각은 `DbTime.now()` 로 만든다. 컬럼(`TIMESTAMP(6)`)과 같은 마이크로초에서 자른 값이다. Linux 의 시계는 나노초까지 주는데 Oracle 은 마이크로초로 반올림해 저장하므로, 자르지 않으면 처음 응답의 시각과 다시 조회한(또는 멱등성 재전송한) 시각이 1µs 어긋난다. macOS 는 시계가 마이크로초라 로컬에서는 안 보이고 CI(Linux)에서 드러났다.

## 테스트

```bash
./gradlew test    # Docker 필요. Testcontainers 가 Oracle 컨테이너를 띄운다
```

- **백엔드 266개**: 엔티티 단위 테스트, MockMvc 웹 계층 테스트, 실제 Oracle 통합 테스트. push·PR 마다 GitHub Actions 가 같은 테스트를 Linux 에서 돌린다(macOS 에서 안 보이던 시각 정밀도 버그를 여기서 찾았다).
  - 동시 출금·이체·해지에서 잔액과 원장이 맞는지
  - 이체 도중 실패하면 양쪽 모두 롤백되는지
  - 같은 키 동시 재전송이 한 번만 처리되는지
  - 코드를 거치지 않은 잘못된 값도 DB 제약이 막는지
  - SQL 로 잔액·원장·이체를 일부러 틀어 놓으면 일 마감 배치가 다섯 가지 불일치를 각각 찾아내는지
  - 배치가 도중에 실패하면 FAILED 로 남고 같은 날을 다시 마감할 수 있는지
  - 고객이 남의 계좌를 조회·입출금·해지·이체해 가려 하면 모두 404 이고 돈이 움직이지 않는지
  - 로그인 잠금(없는 아이디 포함), 잠금 만료·관리자 해제, 동시 시도가 한도를 넘지 못하는지, 수취인 조회 한도와 다음 구간
  - 원장 거래·분개·이체 기록을 SQL 로 고치거나 지우면 DB 가 거부하는지
  - 롤백된 출금, 로그인 실패, 401/403(무효 토큰 포함)도 감사 기록에 남고, 비밀번호는 남지 않으며, 기록을 고치거나 지우면 DB 가 거부하는지
- **API 회귀 171개 요청 / 374개 검증**: [`postman/corebank-api.postman_collection.json`](postman/corebank-api.postman_collection.json). 서버를 띄운 뒤 위에서부터 순서대로 실행한다. 맨 앞 "0. 로그인" 에서 받은 관리자 토큰을 컬렉션 전체가 쓰고, 고객 폴더는 가입해서 받은 고객 토큰을 쓴다. 관리자 계정은 파일에 넣지 않고 실행할 때 넘긴다.

  ```bash
  set -a; . ./.env; set +a
  postman collection run postman/corebank-api.postman_collection.json \
    --env-var "adminUsername=$ADMIN_USERNAME" --env-var "adminPassword=$ADMIN_PASSWORD"
  ```

## DB 마이그레이션

| 버전 | 내용 |
|---|---|
| V1 | 계좌 테이블, 계좌번호 시퀀스 |
| V2 | 원장 (`ledger_transaction`, `ledger_entry`) |
| V3 | 출금 거래 종류 허용 |
| V4 | 이체 테이블, 이체 가계정 |
| V5 | 멱등성 키 기록 |
| V6 | 거래내역 조회 인덱스 `(account_id, id)` |
| V7 | 관리자 계정 |
| V8 | 계좌 해지 (`closed_at`, 해지 지급 거래, 관련 제약) |
| V9 | 일 마감 (Spring Batch `BATCH_*` 테이블, 마감 결과·일계표·불일치 테이블) |
| V10 | 고객 계정, 계좌 주인(`account.customer_id`) |
| V11 | 감사 로그, 수정·삭제를 막는 트리거 |
| V12 | 원장 거래·분개·이체 기록의 수정·삭제를 막는 트리거 |
| V13 | 로그인 시도(`login_attempt`), 횟수 제한(`rate_limit`) |
| R (반복) | 앱 계정 테이블 권한(`R__app_user_grants.sql`). 파일이 바뀔 때마다 다시 실행 |

마이그레이션은 `./gradlew migrate` 로 실행한다(서버는 확인만 한다).

이미 실행된 마이그레이션 파일은 고치지 않는다(Flyway 체크섬 오류). 스키마를 바꿀 때는 새 버전 파일을 추가하고, 새 테이블·시퀀스는 `R__app_user_grants.sql` 에 권한을 추가한다.

## 알려진 한계

- 리프레시 토큰과 토큰 강제 무효화(로그아웃 차단 목록)가 없다. 그래서 토큰 수명을 30분으로 짧게 둔다.
- 일 마감은 서버 한 대를 가정한다. 여러 대를 띄우면 각자 자동 마감을 실행하므로 ShedLock 같은 분산 잠금이 필요하다(같은 날짜 동시 실행은 DB 가 하나만 통과시킨다).
- 일 마감은 불일치를 찾아 기록만 한다. 정정(역분개)과 정정 승인 절차는 없다.
- 해지 잔액은 현금 지급만 지원한다. 다른 계좌로 이체해 주는 방식은 없다.
- 감사 기록은 업무가 끝난 뒤 쓴다. 기록 쓰기가 실패해도(DB 장애 등) 이미 처리된 업무는 되돌리지 않고, 같은 내용을 ERROR 로그로 남긴다. 실서비스는 메시지 큐 등으로 유실 없이 옮기는 장치가 필요하다.
- 테이블 소유자 계정은 트리거를 끄거나 TRUNCATE·DROP 할 수 있다. 서버는 소유자 비밀번호를 읽지 않지만, 개발 환경은 `.env` 하나를 마이그레이션과 서버가 같이 쓰므로 파일에는 들어 있다. 운영에서는 소유자 비밀번호를 배포 단계(마이그레이션 실행)에만 주고 서버 환경에는 두지 않는다(원장은 우회된 변경도 일 마감 대사가 찾아낸다).
- 권한 재부여(`R__app_user_grants.sql`)는 권한을 거둔 뒤 다시 주므로, 서버가 도는 중에 마이그레이션하면 잠깐 권한 오류가 날 수 있다. 서버를 멈추지 않는 배포(무중단)라면 권한을 차이만 주고 거두는 방식이 필요하다.
- 마이그레이션은 뒤로 돌리는 기능(undo)이 없다. 잘못된 변경은 새 버전 파일로 바로잡는다.
- 클라이언트 IP 는 접속한 주소(`remoteAddr`)다. 로드밸런서 뒤에 두면 그 주소가 찍히므로, 믿을 수 있는 프록시의 `X-Forwarded-For` 를 읽도록 설정해야 한다.
- 감사 기록 보관 기간과 오래된 기록 정리(파티션, 별도 보관소 이관)가 없다.
- 로그인 잠금은 아이디마다 센다. 여러 아이디에 흔한 비밀번호 하나씩을 넣어 보는 공격(password spraying)은 아이디마다 1번이라 막지 못한다. IP 단위 제한이나 이상 징후 탐지가 필요하다.
- 남의 아이디를 알면 일부러 틀려서 그 사람을 15분 동안 잠글 수 있다(잠금 방식의 공통 약점). 은행처럼 본인 확인 뒤 관리자가 풀거나, CAPTCHA·추가 인증을 붙이는 것으로 보완한다.
- 잠금 해제 주소는 아이디를 경로에 넣으므로 `/` 가 든 아이디는 풀 수 없다(서버가 경로의 인코딩된 `/` 를 거부한다). 실제 계정은 `/` 를 쓸 수 없어(고객 아이디는 영문 소문자·숫자·밑줄) 이런 아이디는 누군가 아무렇게나 넣어 본 것뿐이고, 15분 뒤 저절로 풀린다.
- 수취인 조회는 고정 구간이라, 구간 경계 앞뒤로 몰아 쓰면 잠깐 동안 한도의 두 배까지 될 수 있다.
