-- 복식부기 원장 테이블. 잔액을 바꾼 모든 거래를 기록하며, 기록은 추가만 하고 수정하거나 삭제하지 않는다.
-- ledger_transaction 은 거래 한 건, ledger_entry 는 그 거래를 구성하는 차변과 대변 분개다.

CREATE SEQUENCE ledger_transaction_seq START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE ledger_transaction (
    id               NUMBER(19)        NOT NULL,
    transaction_type VARCHAR2(20 CHAR) NOT NULL,
    account_id       NUMBER(19)        NOT NULL,
    amount           NUMBER(19)        NOT NULL,
    -- 거래 직후의 잔액. 거래내역 조회와 사후 대사에 쓴다.
    balance_after    NUMBER(19)        NOT NULL,
    created_at       TIMESTAMP(6)      NOT NULL,
    CONSTRAINT pk_ledger_transaction PRIMARY KEY (id),
    CONSTRAINT fk_ledger_transaction_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_ledger_transaction_type CHECK (transaction_type IN ('DEPOSIT')),
    CONSTRAINT ck_ledger_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_transaction_balance CHECK (balance_after >= 0)
);

CREATE TABLE ledger_entry (
    id             NUMBER(19)        NOT NULL,
    transaction_id NUMBER(19)        NOT NULL,
    ledger_account VARCHAR2(20 CHAR) NOT NULL,
    -- 고객 예금 분개에만 고객 계좌를 넣는다. 은행 현금 분개는 고객 계좌가 없다.
    account_id     NUMBER(19),
    entry_side     VARCHAR2(6 CHAR)  NOT NULL,
    amount         NUMBER(19)        NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entry_transaction FOREIGN KEY (transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT fk_ledger_entry_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_ledger_entry_account CHECK (
        (ledger_account = 'CASH' AND account_id IS NULL)
        OR (ledger_account = 'CUSTOMER_DEPOSIT' AND account_id IS NOT NULL)
    ),
    CONSTRAINT ck_ledger_entry_side CHECK (entry_side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amount CHECK (amount > 0)
);

-- Oracle 은 외래키에 인덱스가 없으면 부모 행을 변경할 때 자식 테이블에 더 넓은 락을 걸 수 있다.
-- 첫 번째 인덱스는 이후 거래내역 조회에도 쓴다.
CREATE INDEX ix_ledger_transaction_account ON ledger_transaction (account_id, created_at);
CREATE INDEX ix_ledger_entry_transaction ON ledger_entry (transaction_id);
CREATE INDEX ix_ledger_entry_account ON ledger_entry (account_id);
