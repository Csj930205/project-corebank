-- 계좌 간 이체.
-- 이체 한 건은 transfer 행 1개와 원장 거래 2건(보내는 계좌의 TRANSFER_OUT, 받는 계좌의 TRANSFER_IN)으로 남는다.
-- 거래를 계좌별로 나눠야 각 계좌의 거래내역에 거래 후 잔액(balance_after)이 남는다.

CREATE SEQUENCE transfer_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE transfer (
    id              NUMBER(19)   NOT NULL,
    from_account_id NUMBER(19)   NOT NULL,
    to_account_id   NUMBER(19)   NOT NULL,
    amount          NUMBER(19)   NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_transfer PRIMARY KEY (id),
    CONSTRAINT fk_transfer_from_account FOREIGN KEY (from_account_id) REFERENCES account (id),
    CONSTRAINT fk_transfer_to_account FOREIGN KEY (to_account_id) REFERENCES account (id),
    CONSTRAINT ck_transfer_amount CHECK (amount > 0),
    CONSTRAINT ck_transfer_accounts CHECK (from_account_id <> to_account_id)
);

CREATE INDEX ix_transfer_from_account ON transfer (from_account_id);
CREATE INDEX ix_transfer_to_account ON transfer (to_account_id);

-- 원장 거래에 이체 번호를 연결한다. 입출금은 null, 이체의 두 거래는 같은 번호를 가진다.
ALTER TABLE ledger_transaction ADD (transfer_id NUMBER(19));
ALTER TABLE ledger_transaction ADD CONSTRAINT fk_ledger_transaction_transfer
    FOREIGN KEY (transfer_id) REFERENCES transfer (id);
CREATE INDEX ix_ledger_transaction_transfer ON ledger_transaction (transfer_id);

ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_type;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN'));
-- 이체 거래에는 반드시 이체 번호가 있고, 입출금에는 없어야 한다.
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_transfer CHECK (
    (transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN') AND transfer_id IS NOT NULL)
    OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL') AND transfer_id IS NULL)
);

-- 이체 가계정(TRANSFER_CLEARING)을 허용한다. 은행 내부 계정이라 현금처럼 고객 계좌가 없다.
ALTER TABLE ledger_entry DROP CONSTRAINT ck_ledger_entry_account;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_account CHECK (
    (ledger_account IN ('CASH', 'TRANSFER_CLEARING') AND account_id IS NULL)
    OR (ledger_account = 'CUSTOMER_DEPOSIT' AND account_id IS NOT NULL)
);
