-- 감사 로그. "누가, 언제, 무엇을, 어느 계좌에, 결과는" 을 API 요청마다 한 줄씩 남긴다.
-- 돈이 움직인 요청뿐 아니라 조회(잔액, 거래내역, 수취인 조회, 관리자 목록)와 실패한 시도(로그인 실패, 401/403, 잔액 부족)도 남긴다.
-- 금액은 남기지 않는다. 금액은 원장(ledger_transaction)이 기준이고, 여기에는 원장을 찾아갈 단서(경로, 계좌)만 둔다.

CREATE SEQUENCE audit_log_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE audit_log (
    id                     NUMBER(19)         NOT NULL,
    occurred_at            TIMESTAMP(6)       NOT NULL,
    -- 요청마다 만드는 번호. 응답 헤더 X-Request-Id 로도 내려가, 고객 문의를 이 줄과 바로 이어 붙일 수 있다.
    request_id             VARCHAR2(36 CHAR)  NOT NULL,
    -- ADMIN, CUSTOMER, ANONYMOUS(토큰 없음·무효, 로그인·가입 시도)
    actor_type             VARCHAR2(10 CHAR)  NOT NULL,
    -- 토큰의 아이디. 로그인·가입 요청은 시도한 아이디다(실패해도 남는다).
    actor_username         VARCHAR2(50 CHAR),
    customer_id            NUMBER(19),
    -- AuditAction 이름(DEPOSIT, LOGIN 등)
    action                 VARCHAR2(40 CHAR)  NOT NULL,
    http_method            VARCHAR2(10 CHAR)  NOT NULL,
    -- 실제 요청 경로와 쿼리 문자열. 본문(비밀번호 등)은 남기지 않는다.
    path                   VARCHAR2(500 CHAR) NOT NULL,
    status_code            NUMBER(3)          NOT NULL,
    -- SUCCESS(2xx), DENIED(401/403), FAILURE(그 밖)
    outcome                VARCHAR2(10 CHAR)  NOT NULL,
    -- 실패했을 때의 에러 코드(INSUFFICIENT_BALANCE 등)
    error_code             VARCHAR2(50 CHAR),
    -- 대상 계좌. 이체는 보내는 계좌. 계좌가 지워지지 않으므로 외래키를 걸 수 있지만, 감사 기록 쓰기가 계좌 잠금과 엮이지
    -- 않도록 걸지 않는다(없는 계좌 번호로 조회한 시도도 그대로 남아야 한다).
    target_account_id      NUMBER(19),
    -- 이체 받는 계좌
    counterpart_account_id NUMBER(19),
    client_ip              VARCHAR2(45 CHAR)  NOT NULL,
    user_agent             VARCHAR2(300 CHAR),
    duration_ms            NUMBER(10)         NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('ADMIN', 'CUSTOMER', 'ANONYMOUS')),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);

-- 조회 화면: 기간, 사람, 계좌로 찾고 최신순(id 역순)으로 본다.
CREATE INDEX ix_audit_log_occurred ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_username, id);
CREATE INDEX ix_audit_log_target ON audit_log (target_account_id, id);

-- 한 번 쓴 감사 기록은 고치거나 지울 수 없다. 앱 계정(DB 사용자)이 탈취되어도 흔적을 지우지 못하게 DB 가 막는다.
-- (TRUNCATE·DROP 같은 DDL 은 트리거로 막지 못한다. 운영에서는 테이블 소유자와 앱 계정을 나눠 앱 계정에 INSERT·SELECT 만 준다.)
CREATE OR REPLACE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'audit_log 는 추가만 할 수 있습니다(수정·삭제 불가).');
END;
/
