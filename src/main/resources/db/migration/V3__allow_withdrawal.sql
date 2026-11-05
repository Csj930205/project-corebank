-- 출금 거래 종류를 허용한다. V2 파일을 고치면 Flyway 체크섬 오류가 나므로 새 파일로 제약을 바꾼다.
-- Oracle 은 CHECK 제약을 수정할 수 없어서, 지우고 다시 만든다.
ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_type;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL'));
