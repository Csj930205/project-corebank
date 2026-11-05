-- 계좌 해지. 해지는 행을 지우지 않고 상태를 CLOSED 로 바꾼다. 원장과 거래내역은 계속 남아야 하기 때문이다.
-- 해지할 때 남은 잔액은 고객에게 지급하고(CLOSURE_PAYOUT 거래), 계좌 잔액은 0이 된다.

ALTER TABLE account ADD (closed_at TIMESTAMP(6));

-- 해지 기능 전에 SQL 로 직접 CLOSED 로 바꾼 행이 있으면 해지 시각을 채운다. 시각을 알 수 없어 개설 시각으로 둔다.
UPDATE account SET closed_at = created_at WHERE status = 'CLOSED' AND closed_at IS NULL;

-- 정상 계좌는 해지 시각이 없고, 해지 계좌는 해지 시각이 있으며 잔액이 0이다.
-- 해지 계좌에 돈이 남아 있으면 고객에게 돌려주지 않은 돈이 생긴 것이므로 DB 가 막는다.
ALTER TABLE account ADD CONSTRAINT ck_account_closed CHECK (
    (status = 'ACTIVE' AND closed_at IS NULL)
    OR (status = 'CLOSED' AND closed_at IS NOT NULL AND balance = 0)
);

-- 해지 지급 거래 종류를 허용한다. 입출금처럼 이체 번호가 없다.
ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_type;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'CLOSURE_PAYOUT'));

ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_transfer;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_transfer CHECK (
    (transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN') AND transfer_id IS NOT NULL)
    OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'CLOSURE_PAYOUT') AND transfer_id IS NULL)
);

-- 해지 지급은 계좌당 한 번뿐이다. 함수 기반 유니크 인덱스로, CLOSURE_PAYOUT 행만 account_id 가 값이 되고
-- 나머지 행은 NULL 이 된다. Oracle 은 모든 키가 NULL 인 행을 인덱스에 넣지 않으므로 다른 거래에는 영향이 없다.
CREATE UNIQUE INDEX ux_ledger_closure_payout ON ledger_transaction (
    CASE WHEN transaction_type = 'CLOSURE_PAYOUT' THEN account_id END
);

-- 해지에도 멱등성 키를 쓴다. resource_id 는 해지한 계좌의 account.id 다.
ALTER TABLE idempotency_record DROP CONSTRAINT ck_idempotency_operation;
ALTER TABLE idempotency_record ADD CONSTRAINT ck_idempotency_operation
    CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'CLOSURE'));
