-- 멱등성 키 기록. 돈이 움직이는 요청(입금, 출금, 이체)이 성공하면 키와 요청 내용, 만들어진 거래 번호를 남긴다.
-- 같은 키로 다시 요청이 오면 다시 처리하지 않고 이 기록으로 처음 결과를 돌려준다.
-- 업무 오류(잔액 부족 등)로 실패한 요청은 트랜잭션과 함께 롤백되어 기록이 남지 않으므로, 같은 키로 다시 시도할 수 있다.

CREATE SEQUENCE idempotency_record_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE idempotency_record (
    id                  NUMBER(19)         NOT NULL,
    idempotency_key     VARCHAR2(100 CHAR) NOT NULL,
    operation           VARCHAR2(20 CHAR)  NOT NULL,
    -- 요청 내용 요약(예: DEPOSIT|account=1|amount=1000). 같은 키로 다른 요청이 오면 이 값이 달라 거부한다.
    request_fingerprint VARCHAR2(200 CHAR) NOT NULL,
    -- 입출금은 ledger_transaction.id, 이체는 transfer.id. 대상 테이블이 달라 외래키는 두지 않는다.
    resource_id         NUMBER(19)         NOT NULL,
    created_at          TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    -- 키는 전체에서 한 번만 쓸 수 있다. 동시에 같은 키가 들어와도 DB 가 마지막으로 중복을 막는다.
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_idempotency_operation CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER'))
);
