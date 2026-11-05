-- 거래내역 조회용 인덱스. 조회는 "계좌의 거래를 id 내림차순으로, 특정 id 이전부터 N건"이므로 (account_id, id) 순서가 맞다.
-- 기존 (account_id, created_at) 인덱스는 이 정렬에 쓰이지 않아 교체한다.
-- 새 인덱스도 account_id 로 시작하므로, 외래키 락 문제를 막는 역할(V2 주석 참고)은 그대로 한다.
DROP INDEX ix_ledger_transaction_account;
CREATE INDEX ix_ledger_transaction_account ON ledger_transaction (account_id, id);
