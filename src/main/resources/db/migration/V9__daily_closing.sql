-- 일 마감 대사 배치.
-- 1) Spring Batch 실행 이력 테이블(BATCH_*): spring-batch-core 6.0.6 의 schema-oracle.sql 을 그대로 옮겼다.
--    스프링이 시작할 때 자동으로 만들게 하지 않고(spring.batch.jdbc.initialize-schema=never) Flyway 가 관리한다.
--    다른 테이블과 같은 방식으로 버전 관리하고, 운영 DB 에 무엇이 언제 생겼는지 한곳에서 보기 위해서다.
-- 2) 마감 결과 테이블: daily_closing(실행 한 번), daily_closing_total(일계표), closing_mismatch(불일치 내역).
--    BATCH_* 는 "배치가 돌았는가", 이 테이블들은 "돈이 맞았는가" 를 기록한다.

CREATE TABLE BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID NUMBER(19,0)  NOT NULL PRIMARY KEY,
	VERSION NUMBER(19,0),
	JOB_NAME VARCHAR2(100 char) NOT NULL,
	JOB_KEY VARCHAR2(32 char) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) SEGMENT CREATION IMMEDIATE;

CREATE TABLE BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID NUMBER(19,0)  NOT NULL PRIMARY KEY,
	VERSION NUMBER(19,0),
	JOB_INSTANCE_ID NUMBER(19,0) NOT NULL,
	CREATE_TIME TIMESTAMP(9) NOT NULL,
	START_TIME TIMESTAMP(9) DEFAULT NULL,
	END_TIME TIMESTAMP(9) DEFAULT NULL,
	STATUS VARCHAR2(10 char),
	EXIT_CODE VARCHAR2(2500 char),
	EXIT_MESSAGE VARCHAR2(2500 char),
	LAST_UPDATED TIMESTAMP(9),
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) SEGMENT CREATION IMMEDIATE;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID NUMBER(19,0) NOT NULL,
	PARAMETER_NAME VARCHAR(100 char) NOT NULL,
	PARAMETER_TYPE VARCHAR(100 char) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500 char),
	IDENTIFYING CHAR(1) NOT NULL,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) SEGMENT CREATION IMMEDIATE;

CREATE TABLE BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID NUMBER(19,0)  NOT NULL PRIMARY KEY,
	VERSION NUMBER(19,0) NOT NULL,
	STEP_NAME VARCHAR2(100 char) NOT NULL,
	JOB_EXECUTION_ID NUMBER(19,0) NOT NULL,
	CREATE_TIME TIMESTAMP(9) NOT NULL,
	START_TIME TIMESTAMP(9) DEFAULT NULL,
	END_TIME TIMESTAMP(9) DEFAULT NULL,
	STATUS VARCHAR2(10 char),
	COMMIT_COUNT NUMBER(19,0),
	READ_COUNT NUMBER(19,0),
	FILTER_COUNT NUMBER(19,0),
	WRITE_COUNT NUMBER(19,0),
	READ_SKIP_COUNT NUMBER(19,0),
	WRITE_SKIP_COUNT NUMBER(19,0),
	PROCESS_SKIP_COUNT NUMBER(19,0),
	ROLLBACK_COUNT NUMBER(19,0),
	EXIT_CODE VARCHAR2(2500 char),
	EXIT_MESSAGE VARCHAR2(2500 char),
	LAST_UPDATED TIMESTAMP(9),
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) SEGMENT CREATION IMMEDIATE;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID NUMBER(19,0) NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR2(2500 char) NOT NULL,
	SERIALIZED_CONTEXT CLOB,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) SEGMENT CREATION IMMEDIATE;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID NUMBER(19,0) NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR2(2500 char) NOT NULL,
	SERIALIZED_CONTEXT CLOB,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) SEGMENT CREATION IMMEDIATE;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 ORDER NOCYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 ORDER NOCYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 ORDER NOCYCLE;

CREATE INDEX BATCH_JOB_EXEC_PARAMS_IDX ON BATCH_JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID);

-- ───────────────────────── 마감 결과 ─────────────────────────

CREATE SEQUENCE daily_closing_seq START WITH 1 INCREMENT BY 1 NOCACHE;

-- 마감 실행 한 건. 같은 영업일을 여러 번 실행할 수 있고(불일치를 고친 뒤 재실행), 그 이력이 모두 남는다.
CREATE TABLE daily_closing (
    id                NUMBER(19)          NOT NULL,
    -- 영업일. 이 날 00:00 이상 다음 날 00:00 미만에 생긴 거래가 대상이다(서버 시각 기준).
    business_date     DATE                NOT NULL,
    status            VARCHAR2(10 CHAR)   NOT NULL,
    trigger_type      VARCHAR2(10 CHAR)   NOT NULL,
    -- 수동 실행한 관리자. 자동 실행은 null.
    requested_by      VARCHAR2(50 CHAR),
    -- Spring Batch 실행 번호(BATCH_JOB_EXECUTION). 배치가 시작되기 전에 실패하면 null.
    job_execution_id  NUMBER(19),
    -- 대사한 계좌 수(마감 시점까지 개설된 계좌)
    account_count     NUMBER(19),
    -- 마감 시점 고객 예금(부채) 잔액 합계. 원장 기준이고, 여러 계좌 합이라 long 범위를 넘을 수 있어 38자리로 둔다.
    deposit_liability NUMBER(38),
    mismatch_count    NUMBER(19),
    started_at        TIMESTAMP(6)        NOT NULL,
    finished_at       TIMESTAMP(6),
    error_message     VARCHAR2(1000 CHAR),
    CONSTRAINT pk_daily_closing PRIMARY KEY (id),
    CONSTRAINT ck_daily_closing_status CHECK (status IN ('RUNNING', 'BALANCED', 'UNBALANCED', 'FAILED')),
    CONSTRAINT ck_daily_closing_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    -- 끝난 실행에만 종료 시각이 있다.
    CONSTRAINT ck_daily_closing_finished CHECK (
        (status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL)
    )
);

-- 같은 영업일은 동시에 하나만 실행한다. RUNNING 인 행만 business_date 가 키가 되는 함수 기반 유니크 인덱스다
-- (V8 의 해지 지급 인덱스와 같은 방식). 끝난 실행은 몇 건이 있어도 상관없다.
CREATE UNIQUE INDEX ux_daily_closing_running ON daily_closing (
    CASE WHEN status = 'RUNNING' THEN business_date END
);
CREATE INDEX ix_daily_closing_date ON daily_closing (business_date, id);

-- 일계표: 영업일의 거래 종류별 건수와 금액.
CREATE TABLE daily_closing_total (
    closing_id       NUMBER(19)        NOT NULL,
    transaction_type VARCHAR2(20 CHAR) NOT NULL,
    tx_count         NUMBER(19)        NOT NULL,
    amount           NUMBER(38)        NOT NULL,
    CONSTRAINT pk_daily_closing_total PRIMARY KEY (closing_id, transaction_type),
    CONSTRAINT fk_daily_closing_total FOREIGN KEY (closing_id) REFERENCES daily_closing (id)
);

CREATE SEQUENCE closing_mismatch_seq START WITH 1 INCREMENT BY 1 NOCACHE;

-- 대사에서 찾은 불일치 한 건. expected 는 원장 기준 값, actual 은 비교한 값이다.
CREATE TABLE closing_mismatch (
    id             NUMBER(19)         NOT NULL,
    closing_id     NUMBER(19)         NOT NULL,
    mismatch_type  VARCHAR2(30 CHAR)  NOT NULL,
    -- 불일치 대상. 종류에 따라 하나만 채우거나(계좌, 거래, 이체) 모두 비운다(가계정).
    -- 외래키를 두지 않는다. 불일치 기록은 원본 데이터가 고쳐지거나 지워져도 그대로 남아야 하기 때문이다.
    account_id     NUMBER(19),
    transaction_id NUMBER(19),
    transfer_id    NUMBER(19),
    expected       NUMBER(38),
    actual         NUMBER(38),
    detail         VARCHAR2(500 CHAR),
    CONSTRAINT pk_closing_mismatch PRIMARY KEY (id),
    CONSTRAINT fk_closing_mismatch_closing FOREIGN KEY (closing_id) REFERENCES daily_closing (id),
    CONSTRAINT ck_closing_mismatch_type CHECK (mismatch_type IN (
        'BALANCE_LEDGER_MISMATCH', 'SNAPSHOT_MISMATCH', 'UNBALANCED_TRANSACTION',
        'INCOMPLETE_TRANSFER', 'CLEARING_NOT_ZERO'))
);

CREATE INDEX ix_closing_mismatch_closing ON closing_mismatch (closing_id, id);
