-- 거래 정정(역분개)과 승인 절차(maker-checker).
--
-- 원장은 고치지 않는다(V12). 잘못된 거래는 반대 방향 거래(역분개)를 새로 추가해 효과를 없앤다.
-- 원래 거래와 역분개가 둘 다 남으므로 "언제 무엇이 잘못 들어왔고 언제 누가 바로잡았는지"가 원장에 그대로 보인다.
--
-- 정정은 혼자 할 수 없다. 한 관리자가 요청하고(maker) 다른 관리자가 승인해야(checker) 역분개가 들어간다.
-- 한 사람이 돈을 마음대로 옮길 수 없게 하는 금융권 내부통제의 기본이다.

-- ───────────────────────── 원장: 역분개 거래 ─────────────────────────

-- 역분개가 어떤 거래를 되돌렸는지. 역분개 거래에만 값이 있다.
ALTER TABLE ledger_transaction ADD (reversal_of_id NUMBER(19));
ALTER TABLE ledger_transaction ADD CONSTRAINT fk_ledger_transaction_reversal
    FOREIGN KEY (reversal_of_id) REFERENCES ledger_transaction (id);
-- 한 거래는 한 번만 되돌릴 수 있다. 값이 NULL 인 행(일반 거래)은 Oracle 이 인덱스에 넣지 않으므로 서로 겹치지 않는다.
-- 이 인덱스는 외래키 인덱스 역할도 한다.
CREATE UNIQUE INDEX ux_ledger_transaction_reversal ON ledger_transaction (reversal_of_id);

-- 역분개는 고객 잔액이 움직이는 방향으로 두 종류다.
-- REVERSAL_OUT: 잔액이 준다(입금, 이체 입금을 되돌림).  REVERSAL_IN: 잔액이 는다(출금, 이체 출금을 되돌림).
ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_type;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'CLOSURE_PAYOUT',
                                'REVERSAL_OUT', 'REVERSAL_IN'));

-- 역분개는 이체 번호를 갖지 않는다. 같은 번호를 주면 일 마감의 "이체 한 건 = 원장 거래 2건" 점검이 깨진다.
-- 이체를 되돌린 역분개는 reversal_of_id 로 원래 이체 거래를 따라가면 찾을 수 있다.
ALTER TABLE ledger_transaction DROP CONSTRAINT ck_ledger_transaction_transfer;
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_transfer CHECK (
    (transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN') AND transfer_id IS NOT NULL)
    OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'CLOSURE_PAYOUT', 'REVERSAL_OUT', 'REVERSAL_IN')
        AND transfer_id IS NULL)
);

-- 역분개에는 반드시 원래 거래가 있고, 다른 거래에는 없다.
ALTER TABLE ledger_transaction ADD CONSTRAINT ck_ledger_transaction_reversal CHECK (
    (transaction_type IN ('REVERSAL_OUT', 'REVERSAL_IN') AND reversal_of_id IS NOT NULL)
    OR (transaction_type NOT IN ('REVERSAL_OUT', 'REVERSAL_IN') AND reversal_of_id IS NULL)
);

-- ───────────────────────── 정정 요청 ─────────────────────────

CREATE SEQUENCE correction_request_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE correction_request (
    id               NUMBER(19)         NOT NULL,
    -- 되돌릴 원장 거래. 이체는 두 거래(출금 쪽, 입금 쪽)를 함께 되돌리며, 여기에는 출금 쪽 거래를 넣는다.
    transaction_id   NUMBER(19)         NOT NULL,
    reason           VARCHAR2(500 CHAR) NOT NULL,
    status           VARCHAR2(10 CHAR)  NOT NULL,
    requested_by     VARCHAR2(50 CHAR)  NOT NULL,
    requested_at     TIMESTAMP(6)       NOT NULL,
    decided_by       VARCHAR2(50 CHAR),
    decided_at       TIMESTAMP(6),
    decision_comment VARCHAR2(500 CHAR),
    -- 낙관적 락(JPA @Version). 두 관리자가 같은 요청을 동시에 승인·반려하면 나중 쪽이 실패한다.
    version          NUMBER(19)         NOT NULL,
    CONSTRAINT pk_correction_request PRIMARY KEY (id),
    CONSTRAINT fk_correction_request_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT ck_correction_request_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    -- 결정된 요청에만 결정한 사람과 시각이 있다.
    CONSTRAINT ck_correction_request_decided CHECK (
        (status = 'REQUESTED' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'REQUESTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    -- 요청한 사람은 스스로 승인·반려할 수 없다(4-eyes 원칙). 코드가 먼저 막지만 DB 도 한 번 더 막는다.
    CONSTRAINT ck_correction_request_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- 한 거래에 진행 중인 요청은 하나만. REQUESTED 인 행만 키가 되는 함수 기반 유니크 인덱스다(V8, V9 와 같은 방식).
-- 반려된 요청은 몇 건이 있어도 되므로, 반려 뒤에 다시 요청할 수 있다.
CREATE UNIQUE INDEX ux_correction_request_open ON correction_request (
    CASE WHEN status = 'REQUESTED' THEN transaction_id END
);
CREATE INDEX ix_correction_request_tx ON correction_request (transaction_id);
CREATE INDEX ix_correction_request_status ON correction_request (status, id);
