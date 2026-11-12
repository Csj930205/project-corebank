-- 월 이자 결산을 Spring Batch 잡(interestSettlementJob)으로 옮긴다.
--
-- 지급 기록에 "어느 배치 실행이 지급했는가" 를 남긴다. 결산 결과(지급 계좌 수, 세전·세금·세후 합계)를
-- 이 열로 모아 계산하고, 나중에 "이 이자는 언제 어떤 실행에서 나갔나" 를 BATCH_JOB_EXECUTION 까지 따라갈 수 있다.
-- 해지 지급(CLOSURE)과 이 마이그레이션 전의 결산은 배치가 아니라 null 이다.
ALTER TABLE interest_payment ADD (job_execution_id NUMBER(19));

ALTER TABLE interest_payment ADD CONSTRAINT fk_interest_payment_job
    FOREIGN KEY (job_execution_id) REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID);

-- 해지 지급은 배치가 하지 않는다.
ALTER TABLE interest_payment ADD CONSTRAINT ck_interest_payment_job
    CHECK (trigger_type = 'SETTLEMENT' OR job_execution_id IS NULL);

CREATE INDEX ix_interest_payment_job ON interest_payment (job_execution_id);
