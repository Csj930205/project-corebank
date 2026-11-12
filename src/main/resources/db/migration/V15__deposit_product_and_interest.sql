-- 예금 상품과 이자.
--
-- 이자 계산은 은행에서 쓰는 "적수" 방식이다. 매일 마감 잔액을 하루치씩 기록해 두고(interest_accrual),
-- 결산 때 "Σ(일별 잔액 × 연이율) ÷ 365" 를 한 번만 계산해 원 미만을 버린다.
-- 하루 이자를 매일 원 단위로 잘라 더하면 소액 계좌는 매일 0원이 되어 이자가 사라지고,
-- 소수로 쌓으면 반올림 오차가 쌓인다. 적수는 정수(잔액)와 소수 둘째 자리(이율)의 곱이라 마지막 나눗셈 전까지 오차가 없다.

-- ───────────────────────── 예금 상품 ─────────────────────────

-- 1번은 아래에서 직접 넣는 기본 상품이라 시퀀스는 2부터 시작한다.
CREATE SEQUENCE deposit_product_seq START WITH 2 INCREMENT BY 1 NOCACHE;

CREATE TABLE deposit_product (
    id              NUMBER(19)        NOT NULL,
    name            VARCHAR2(50 CHAR) NOT NULL,
    -- 연이율(%). 2.35 = 연 2.35%. 소수 둘째 자리까지. 바꾸지 않는다: 금리를 바꾸려면 새 상품을 만든다.
    annual_rate     NUMBER(5, 2)      NOT NULL,
    status          VARCHAR2(12 CHAR) NOT NULL,
    created_at      TIMESTAMP(6)      NOT NULL,
    discontinued_at TIMESTAMP(6),
    CONSTRAINT pk_deposit_product PRIMARY KEY (id),
    CONSTRAINT uk_deposit_product_name UNIQUE (name),
    CONSTRAINT ck_deposit_product_rate CHECK (annual_rate >= 0 AND annual_rate <= 20),
    CONSTRAINT ck_deposit_product_status CHECK (
        (status = 'ACTIVE' AND discontinued_at IS NULL)
        OR (status = 'DISCONTINUED' AND discontinued_at IS NOT NULL)
    )
);

-- 기본 상품. 상품 기능 전에 만든 계좌와, 상품을 고르지 않고 연 계좌가 이 상품이다.
INSERT INTO deposit_product (id, name, annual_rate, status, created_at)
VALUES (1, '기본 입출금 통장', 0.10, 'ACTIVE', LOCALTIMESTAMP);

-- 계좌의 상품. DEFAULT 1 로 기존 계좌를 모두 기본 상품으로 채운다. 한 번 정하면 바꾸지 않는다(앱 계정 UPDATE 권한 없음).
ALTER TABLE account ADD (product_id NUMBER(19) DEFAULT 1 NOT NULL);
ALTER TABLE account ADD CONSTRAINT fk_account_product FOREIGN KEY (product_id) REFERENCES deposit_product (id);
CREATE INDEX ix_account_product ON account (product_id);

-- ───────────────────────── 원장: 이자 거래 ─────────────────────────

-- 이자 지급 거래. 고객 잔액이 는다. 이체·역분개가 아니므로 이체 번호와 원래 거래가 없다.
ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_type;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'CLOSURE_PAYOUT',
                                'REVERSAL_OUT', 'REVERSAL_IN', 'INTEREST'));

ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_transfer;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_transfer CHECK (
    (transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN') AND transfer_id IS NOT NULL)
    OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'CLOSURE_PAYOUT', 'REVERSAL_OUT', 'REVERSAL_IN', 'INTEREST')
        AND transfer_id IS NULL)
);

-- 이자 분개에 쓰는 은행 내부 계정과목. 고객 계좌가 없다.
--   INTEREST_EXPENSE     이자비용(비용). 은행이 지급한 이자 전액(세전)
--   INTEREST_TAX_PAYABLE 이자소득세 예수금(부채). 고객 대신 떼어 두었다가 세무서에 낼 돈
-- 이자 지급 분개: 차변 이자비용(세전) / 대변 고객 예금(세후) + 대변 예수금(세금)
ALTER TABLE ledger_entry DROP CONSTRAINT ck_ledger_entry_account;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_account CHECK (
    (ledger_account IN ('CASH', 'TRANSFER_CLEARING', 'INTEREST_EXPENSE', 'INTEREST_TAX_PAYABLE') AND account_id IS NULL)
    OR (ledger_account = 'CUSTOMER_DEPOSIT' AND account_id IS NOT NULL)
);

-- ───────────────────────── 이자 지급 ─────────────────────────

CREATE SEQUENCE interest_payment_seq START WITH 1 INCREMENT BY 1 NOCACHE;

-- 이자 지급 한 건. 월 결산(SETTLEMENT)이나 해지(CLOSURE) 때 그때까지 쌓인 적수로 계산한다.
CREATE TABLE interest_payment (
    id                    NUMBER(19)        NOT NULL,
    account_id            NUMBER(19)        NOT NULL,
    trigger_type          VARCHAR2(10 CHAR) NOT NULL,
    period_from           DATE              NOT NULL,
    period_to             DATE              NOT NULL,
    day_count             NUMBER(5)         NOT NULL,
    -- Σ(일별 잔액 × 연이율%). 세전 이자 = FLOOR(이 값 / 36500)
    balance_rate_sum      NUMBER(38, 2)     NOT NULL,
    gross_amount          NUMBER(19)        NOT NULL,
    income_tax            NUMBER(19)        NOT NULL,
    local_tax             NUMBER(19)        NOT NULL,
    net_amount            NUMBER(19)        NOT NULL,
    -- 이자가 0원이면 원장 거래가 없다(원장 금액은 1원 이상).
    ledger_transaction_id NUMBER(19),
    paid_at               TIMESTAMP(6)      NOT NULL,
    CONSTRAINT pk_interest_payment PRIMARY KEY (id),
    CONSTRAINT fk_interest_payment_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_interest_payment_tx FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT ck_interest_payment_trigger CHECK (trigger_type IN ('SETTLEMENT', 'CLOSURE')),
    CONSTRAINT ck_interest_payment_period CHECK (period_from <= period_to AND day_count > 0),
    -- 세전 = 세후 + 세금. 금액은 음수가 아니다.
    CONSTRAINT ck_interest_payment_amounts CHECK (
        gross_amount = net_amount + income_tax + local_tax
        AND net_amount >= 0 AND income_tax >= 0 AND local_tax >= 0
    ),
    CONSTRAINT ck_interest_payment_tx CHECK (
        (gross_amount = 0 AND ledger_transaction_id IS NULL)
        OR (gross_amount > 0 AND ledger_transaction_id IS NOT NULL)
    )
);
CREATE INDEX ix_interest_payment_account ON interest_payment (account_id, id);
CREATE UNIQUE INDEX ux_interest_payment_tx ON interest_payment (ledger_transaction_id);

-- ───────────────────────── 일별 적수 ─────────────────────────

-- 계좌의 하루치 마감 잔액과 그날 연이율. 하루에 한 번만 쌓인다(PK). 결산에서 지급되면 payment_id 가 채워진다.
-- 잔액 0인 날은 적수가 0이라 남기지 않는다.
CREATE TABLE interest_accrual (
    account_id   NUMBER(19)   NOT NULL,
    accrual_date DATE         NOT NULL,
    balance      NUMBER(19)   NOT NULL,
    annual_rate  NUMBER(5, 2) NOT NULL,
    payment_id   NUMBER(19),
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_interest_accrual PRIMARY KEY (account_id, accrual_date),
    CONSTRAINT fk_interest_accrual_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_interest_accrual_payment FOREIGN KEY (payment_id) REFERENCES interest_payment (id),
    CONSTRAINT ck_interest_accrual_balance CHECK (balance > 0)
);
CREATE INDEX ix_interest_accrual_payment ON interest_accrual (payment_id);
CREATE INDEX ix_interest_accrual_date ON interest_accrual (accrual_date);
