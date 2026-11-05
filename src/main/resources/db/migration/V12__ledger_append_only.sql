-- 원장 불변성. 원장 거래, 분개, 이체 기록은 추가만 할 수 있고 고치거나 지울 수 없다.
-- 지금까지는 코드가 UPDATE·DELETE 를 하지 않는 것으로만 지켰다. 이제 DB 가 막아서, 코드 버그나 SQL 직접 실행으로도
-- 돈의 흔적이 바뀌지 않는다. 잘못된 거래는 고치지 않고 반대 거래(역분개)를 새로 추가해 바로잡는 것이 원장의 규칙이다.
--
-- 계좌 잔액(account.balance)은 막지 않는다. 거래마다 바뀌는 값이고, 원장 합계와 같은지는 일 마감 대사가 매일 확인한다.
-- 트리거를 우회한 변경(테이블 소유자의 DDL 등)도 일 마감 대사가 찾아낸다. 막기(트리거)와 찾기(대사)의 두 겹이다.
--
-- 행 단위가 아니라 문장 단위 트리거라, 바뀌는 행이 0건인 UPDATE·DELETE 도 거부한다. 원장에 그런 문장을 보낼 이유가 없다.

CREATE OR REPLACE TRIGGER trg_ledger_transaction_append_only
    BEFORE UPDATE OR DELETE ON ledger_transaction
BEGIN
    RAISE_APPLICATION_ERROR(-20002, 'ledger_transaction 은 추가만 할 수 있습니다(수정·삭제 불가). 정정은 역분개로 합니다.');
END;
/

CREATE OR REPLACE TRIGGER trg_ledger_entry_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
BEGIN
    RAISE_APPLICATION_ERROR(-20002, 'ledger_entry 는 추가만 할 수 있습니다(수정·삭제 불가). 정정은 역분개로 합니다.');
END;
/

CREATE OR REPLACE TRIGGER trg_transfer_append_only
    BEFORE UPDATE OR DELETE ON transfer
BEGIN
    RAISE_APPLICATION_ERROR(-20002, 'transfer 는 추가만 할 수 있습니다(수정·삭제 불가).');
END;
/
