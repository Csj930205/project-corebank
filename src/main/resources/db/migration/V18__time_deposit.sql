-- 정기예금과 금리 변경 이력.
--
-- 1. 상품 종류: 입출금(DEMAND)과 정기예금(TIME). 한 테이블에 두고 product_type 으로 나눈다(JPA SINGLE_TABLE 상속).
-- 2. 금리 변경 이력: 상품 금리를 바꿀 수 있게 하고, 바뀐 날짜와 금리를 모두 남긴다. 적수는 "그날 적용된 금리" 로 쌓는다.
-- 3. 정기예금 계약: 계좌 하나 = 정기예금 하나. 원금은 연결계좌(입출금)에서 옮겨 오고, 만기·중도해지 때 원리금을 연결계좌로 돌려준다.

-- ───────────────────────── 상품 종류 ─────────────────────────

-- 기존 상품은 모두 입출금 상품이다. term_months 는 정기예금만 쓴다(가입 기간, 개월).
ALTER TABLE deposit_product ADD (
    product_type VARCHAR2(10 CHAR) DEFAULT 'DEMAND' NOT NULL,
    term_months  NUMBER(3)
);
ALTER TABLE deposit_product ADD CONSTRAINT ck_deposit_product_type CHECK (
    (product_type = 'DEMAND' AND term_months IS NULL)
    OR (product_type = 'TIME' AND term_months BETWEEN 1 AND 60)
);

-- ───────────────────────── 금리 변경 이력 ─────────────────────────

CREATE SEQUENCE deposit_product_rate_seq START WITH 1 INCREMENT BY 1 NOCACHE;

-- 상품 금리의 이력. effective_from 날짜부터 이 금리다. 같은 날 두 번 바꾸면 나중 것(id 가 큰 것)이 그날의 금리다.
-- 추가만 한다(앱 계정은 INSERT 만). deposit_product.annual_rate 는 가장 최근 금리를 그대로 옮겨 둔 값이다(목록 화면용).
--   입출금 상품: 매일 적수를 쌓을 때 그날의 금리를 여기서 찾는다(변동 금리).
--   정기예금 상품: 가입하는 순간의 금리를 계약에 복사한다(고정 금리). 나중에 금리가 바뀌어도 이미 든 예금은 그대로다.
CREATE TABLE deposit_product_rate (
    id             NUMBER(19)         NOT NULL,
    product_id     NUMBER(19)         NOT NULL,
    annual_rate    NUMBER(5, 2)       NOT NULL,
    effective_from DATE               NOT NULL,
    -- 바꾼 관리자 아이디. 상품을 만들 때의 첫 금리와 이 마이그레이션이 옮긴 금리는 null
    changed_by     VARCHAR2(50 CHAR),
    created_at     TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_deposit_product_rate PRIMARY KEY (id),
    CONSTRAINT fk_deposit_product_rate_product FOREIGN KEY (product_id) REFERENCES deposit_product (id),
    CONSTRAINT ck_deposit_product_rate_rate CHECK (annual_rate >= 0 AND annual_rate <= 20)
);
CREATE INDEX ix_deposit_product_rate ON deposit_product_rate (product_id, effective_from, id);

-- 기존 상품의 첫 금리. 상품이 생긴 날부터다.
INSERT INTO deposit_product_rate (id, product_id, annual_rate, effective_from, created_at)
SELECT deposit_product_rate_seq.NEXTVAL, id, annual_rate, TRUNC(created_at), LOCALTIMESTAMP FROM deposit_product;

-- ───────────────────────── 정기예금 계약 ─────────────────────────

-- 정기예금 한 건. 기본키가 곧 계좌 id 다(계좌와 1:1, JPA @MapsId).
--   ACTIVE     가입 중
--   MATURED    만기 해지. 약정이율로 이자를 주고 원리금을 연결계좌로 보냈다
--   TERMINATED 중도 해지. 중도해지이율로 이자를 주고 원리금을 연결계좌로 보냈다
CREATE TABLE time_deposit (
    account_id          NUMBER(19)        NOT NULL,
    product_id          NUMBER(19)        NOT NULL,
    principal           NUMBER(19)        NOT NULL,
    -- 약정이율(%). 가입할 때 상품 금리를 복사한다.
    annual_rate         NUMBER(5, 2)      NOT NULL,
    term_months         NUMBER(3)         NOT NULL,
    -- 이자는 start_date 부터 끝나는 날 전날까지 붙는다(한편넣기).
    start_date          DATE              NOT NULL,
    maturity_date       DATE              NOT NULL,
    -- 원금을 꺼내 온 계좌이자, 만기·중도해지 때 원리금을 받을 계좌
    payout_account_id   NUMBER(19)        NOT NULL,
    status              VARCHAR2(10 CHAR) NOT NULL,
    -- 해지할 때 실제로 쓴 이율. 만기면 약정이율, 중도해지면 중도해지이율
    applied_rate        NUMBER(5, 2),
    -- 이자 지급 기록. 가입 당일 해지처럼 이자 붙은 날이 0일이면 지급 기록이 없다
    interest_payment_id NUMBER(19),
    -- 원리금을 연결계좌로 보낸 이체
    payout_transfer_id  NUMBER(19),
    settled_at          TIMESTAMP(6),
    created_at          TIMESTAMP(6)      NOT NULL,
    CONSTRAINT pk_time_deposit PRIMARY KEY (account_id),
    CONSTRAINT fk_time_deposit_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_time_deposit_product FOREIGN KEY (product_id) REFERENCES deposit_product (id),
    CONSTRAINT fk_time_deposit_payout FOREIGN KEY (payout_account_id) REFERENCES account (id),
    CONSTRAINT fk_time_deposit_interest FOREIGN KEY (interest_payment_id) REFERENCES interest_payment (id),
    CONSTRAINT fk_time_deposit_transfer FOREIGN KEY (payout_transfer_id) REFERENCES transfer (id),
    CONSTRAINT ck_time_deposit_principal CHECK (principal > 0),
    CONSTRAINT ck_time_deposit_rate CHECK (annual_rate >= 0 AND annual_rate <= 20),
    CONSTRAINT ck_time_deposit_period CHECK (start_date < maturity_date AND term_months BETWEEN 1 AND 60),
    CONSTRAINT ck_time_deposit_payout CHECK (payout_account_id <> account_id),
    -- 상태와 해지 기록이 짝이 맞는다. 해지했으면 원리금 이체가 반드시 있다(원금이 1원 이상이므로).
    CONSTRAINT ck_time_deposit_status CHECK (
        (status = 'ACTIVE' AND applied_rate IS NULL AND interest_payment_id IS NULL
            AND payout_transfer_id IS NULL AND settled_at IS NULL)
        OR (status IN ('MATURED', 'TERMINATED') AND applied_rate IS NOT NULL
            AND payout_transfer_id IS NOT NULL AND settled_at IS NOT NULL)
    )
);
-- 만기 처리 대상 찾기(상태, 만기일)와 연결계좌 해지 전 확인(연결계좌, 상태)
CREATE INDEX ix_time_deposit_maturity ON time_deposit (status, maturity_date);
CREATE INDEX ix_time_deposit_payout ON time_deposit (payout_account_id, status);
CREATE INDEX ix_time_deposit_product ON time_deposit (product_id);

-- ───────────────────────── 이자 지급·멱등성 종류 ─────────────────────────

-- 정기예금 이자: 만기(MATURITY), 중도해지(EARLY_TERM)
ALTER TABLE interest_payment DROP CONSTRAINT ck_interest_payment_trigger;
ALTER TABLE interest_payment ADD CONSTRAINT ck_interest_payment_trigger
    CHECK (trigger_type IN ('SETTLEMENT', 'CLOSURE', 'MATURITY', 'EARLY_TERM'));

-- 정기예금 가입(resource_id = 새 정기예금 계좌 id), 중도해지(resource_id = 정기예금 계좌 id)
ALTER TABLE idempotency_record DROP CONSTRAINT ck_idempotency_operation;
ALTER TABLE idempotency_record ADD CONSTRAINT ck_idempotency_operation
    CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'CLOSURE', 'TIME_DEPOSIT_OPEN', 'TIME_DEPOSIT_CLOSE'));
