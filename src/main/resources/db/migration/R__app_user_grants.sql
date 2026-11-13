-- 앱 실행 계정(${app_user})의 테이블 권한. 테이블 소유자가 Flyway 로 실행한다.
--
-- 반복 실행 마이그레이션(R__)이라 이 파일이 바뀔 때마다 버전 마이그레이션(V__) 뒤에 다시 실행된다.
-- 먼저 앱 계정에 준 권한을 모두 거두고 아래 목록대로 다시 준다. 그래서 이 파일이 곧 앱 계정 권한의 전체 목록이다.
-- 새 테이블·시퀀스를 만들면 여기에도 추가해야 앱이 쓸 수 있다(빠뜨리면 ORA-00942 로 테스트가 깨진다).
--
-- 원칙: 앱이 실제로 하는 일만 허용한다.
-- - 원장·이체·감사 기록: SELECT, INSERT 만. UPDATE·DELETE 권한이 없으니 트리거(V11, V12)에 닿기 전에 권한에서 막힌다.
-- - 계좌: 바뀌는 열(잔액, 상태, 해지 시각)만 UPDATE. 계좌번호·이름·개설일은 앱도 못 바꾼다. DELETE 없음.
-- - DDL 은 전혀 없다. 앱 계정은 자기 스키마에 객체를 만들 수도 없고(CREATE SESSION 만 있다),
--   소유자의 트리거를 끄거나(ALTER TRIGGER) TRUNCATE·DROP 할 수도 없다(소유자만 할 수 있다).

BEGIN
    FOR p IN (SELECT DISTINCT table_name, privilege FROM user_tab_privs_made WHERE grantee = UPPER('${app_user}')
              UNION
              SELECT DISTINCT table_name, privilege FROM user_col_privs_made WHERE grantee = UPPER('${app_user}')) LOOP
        EXECUTE IMMEDIATE 'REVOKE ' || p.privilege || ' ON "' || p.table_name || '" FROM ${app_user}';
    END LOOP;
END;
/

-- 계좌
GRANT SELECT, INSERT ON account TO ${app_user};
GRANT UPDATE (balance, status, closed_at) ON account TO ${app_user};
GRANT SELECT ON account_seq TO ${app_user};
GRANT SELECT ON account_number_seq TO ${app_user};

-- 원장과 이체: 추가만
GRANT SELECT, INSERT ON ledger_transaction TO ${app_user};
GRANT SELECT, INSERT ON ledger_entry TO ${app_user};
GRANT SELECT, INSERT ON transfer TO ${app_user};
GRANT SELECT ON ledger_transaction_seq TO ${app_user};
GRANT SELECT ON ledger_entry_seq TO ${app_user};
GRANT SELECT ON transfer_seq TO ${app_user};

-- 멱등성 키: 한 번 저장하면 바꾸지 않는다
GRANT SELECT, INSERT ON idempotency_record TO ${app_user};
GRANT SELECT ON idempotency_record_seq TO ${app_user};

-- 관리자·고객: 가입(시작 시 관리자 생성)과 조회. 비밀번호 변경 기능이 생기면 그 열만 UPDATE 를 준다.
-- 관리자 등록 승인·반려는 결정 관련 열만 바꾼다. 아이디·역할·요청자는 앱도 못 바꾼다(역할을 몰래 올릴 수 없다).
GRANT SELECT, INSERT ON admin_user TO ${app_user};
GRANT UPDATE (status, decided_by, decided_at, version) ON admin_user TO ${app_user};
GRANT SELECT, INSERT ON customer TO ${app_user};
GRANT SELECT ON admin_user_seq TO ${app_user};
GRANT SELECT ON customer_seq TO ${app_user};

-- 감사 로그: 추가만
GRANT SELECT, INSERT ON audit_log TO ${app_user};
GRANT SELECT ON audit_log_seq TO ${app_user};

-- 횟수 제한: 세고, 성공·해제·정리할 때 지운다
GRANT SELECT, INSERT, UPDATE, DELETE ON login_attempt TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON rate_limit TO ${app_user};

-- 일 마감: 실행 기록은 상태를 바꾸고, 합계·불일치는 추가만
GRANT SELECT, INSERT, UPDATE ON daily_closing TO ${app_user};
GRANT SELECT, INSERT ON daily_closing_total TO ${app_user};
GRANT SELECT, INSERT ON closing_mismatch TO ${app_user};
GRANT SELECT ON daily_closing_seq TO ${app_user};
GRANT SELECT ON closing_mismatch_seq TO ${app_user};

-- 거래 정정 요청: 만들고, 결정(승인·반려)할 때 결정 관련 열만 바꾼다. 대상 거래·사유·요청자는 앱도 못 바꾼다.
GRANT SELECT, INSERT ON correction_request TO ${app_user};
GRANT UPDATE (status, decided_by, decided_at, decision_comment, version) ON correction_request TO ${app_user};
GRANT SELECT ON correction_request_seq TO ${app_user};

-- 예금 상품: 등록, 판매 중지(상태·중지 시각), 금리 변경(현재 금리)만. 이름·종류·기간은 바꾸지 않는다.
-- 금리 이력은 추가만 한다. 지난 금리를 고치면 이미 쌓인 적수와 맞지 않게 된다.
GRANT SELECT, INSERT ON deposit_product TO ${app_user};
GRANT UPDATE (status, discontinued_at, annual_rate) ON deposit_product TO ${app_user};
GRANT SELECT ON deposit_product_seq TO ${app_user};
GRANT SELECT, INSERT ON deposit_product_rate TO ${app_user};
GRANT SELECT ON deposit_product_rate_seq TO ${app_user};

-- 정기예금: 가입하고, 해지할 때 상태와 해지 기록 열만 바꾼다. 원금·약정이율·만기일·연결계좌는 앱도 못 바꾼다.
GRANT SELECT, INSERT ON time_deposit TO ${app_user};
GRANT UPDATE (status, applied_rate, interest_payment_id, payout_transfer_id, settled_at) ON time_deposit TO ${app_user};

-- 이자: 적수는 쌓고 지급할 때 지급 번호만 채운다. 지급 기록은 추가만.
GRANT SELECT, INSERT ON interest_accrual TO ${app_user};
GRANT UPDATE (payment_id) ON interest_accrual TO ${app_user};
GRANT SELECT, INSERT ON interest_payment TO ${app_user};
GRANT SELECT ON interest_payment_seq TO ${app_user};

-- 마이그레이션 이력: 서버가 시작할 때 DB 버전을 확인(validate)만 한다. Flyway 가 이름을 소문자로 만들어 따옴표가 필요하다.
GRANT SELECT ON "flyway_schema_history" TO ${app_user};

-- Spring Batch 잡 저장소: 실행 기록을 만들고 진행 상태를 고친다. 지우지는 않는다.
GRANT SELECT, INSERT, UPDATE ON BATCH_JOB_INSTANCE TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON BATCH_JOB_EXECUTION TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON BATCH_JOB_EXECUTION_PARAMS TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON BATCH_STEP_EXECUTION TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON BATCH_STEP_EXECUTION_CONTEXT TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON BATCH_JOB_EXECUTION_CONTEXT TO ${app_user};
GRANT SELECT ON BATCH_JOB_INSTANCE_SEQ TO ${app_user};
GRANT SELECT ON BATCH_JOB_EXECUTION_SEQ TO ${app_user};
GRANT SELECT ON BATCH_STEP_EXECUTION_SEQ TO ${app_user};
