-- 관리자 역할 분리와 관리자 등록 승인.
--
-- 지금까지는 관리자면 무엇이든 할 수 있었고, 관리자가 관리자를 바로 만들 수 있었다. 그래서 한 사람이 계정을 하나 더 만들어
-- 자기 정정 요청을 자기가 승인할 수 있었다(4-eyes 가 이름뿐). 이제 관리자마다 역할을 하나만 두고, 역할끼리 일을 나눈다.
--   TELLER   창구:   계좌 개설·입출금·이체·해지, 거래 정정 요청
--   APPROVER 책임자: 거래 정정 승인·반려, 관리자 등록 승인·반려
--   OPERATOR 운영:   일 마감, 이자 적립·결산, 상품 등록·중지, 로그인 잠금 해제, 관리자 등록 요청
-- 조회는 역할과 상관없이 모든 관리자가 한다. 역할은 한 번 정하면 바꾸지 않는다(앱 계정 UPDATE 권한 없음).
--
-- 관리자 등록은 OPERATOR 가 요청하고(PENDING) APPROVER 가 승인해야(ACTIVE) 로그인할 수 있다.
-- 요청자와 승인자는 역할이 달라 반드시 다른 계정이고, 아래 CHECK 가 한 번 더 막는다.

-- 기존 관리자는 운영자로 둔다. 돈을 직접 움직이는 창구나 승인하는 책임자를 아무 확인 없이 줄 수는 없어서다.
ALTER TABLE admin_user ADD (
    role         VARCHAR2(10 CHAR) DEFAULT 'OPERATOR' NOT NULL,
    status       VARCHAR2(10 CHAR) DEFAULT 'ACTIVE' NOT NULL,
    -- 등록을 요청한 관리자. 서버 시작 때 .env 로 만든 계정과 이 마이그레이션 전부터 있던 계정은 null 이다.
    requested_by VARCHAR2(50 CHAR),
    decided_by   VARCHAR2(50 CHAR),
    decided_at   TIMESTAMP(6),
    -- 두 책임자가 같은 요청을 동시에 승인·반려하면 나중 쪽이 실패한다(낙관적 잠금, correction_request 와 같다).
    version      NUMBER(10) DEFAULT 0 NOT NULL
);

-- 새 행은 앱이 값을 정해 넣는다. 기본값은 위에서 기존 행을 채우는 데만 쓰고 지운다.
ALTER TABLE admin_user MODIFY (role DEFAULT NULL, status DEFAULT NULL);

ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role CHECK (role IN ('TELLER', 'APPROVER', 'OPERATOR'));
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED'));
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_decision CHECK (
    -- 서버 설정(.env)으로 만든 계정: 요청도 결정도 없이 바로 ACTIVE
    (requested_by IS NULL AND status = 'ACTIVE' AND decided_by IS NULL AND decided_at IS NULL)
    -- 승인을 기다리는 계정
    OR (requested_by IS NOT NULL AND status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
    -- 결정된 계정. 요청한 사람이 결정할 수 없다.
    OR (requested_by IS NOT NULL AND status IN ('ACTIVE', 'REJECTED')
        AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_by <> requested_by)
);
