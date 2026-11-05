-- 관리자 계정. 비밀번호는 원문이 아니라 BCrypt 해시로만 저장한다.
-- 첫 관리자는 서버가 시작할 때 .env 의 ADMIN_USERNAME / ADMIN_PASSWORD 로 한 번만 만든다(AdminBootstrap).

CREATE SEQUENCE admin_user_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE admin_user (
    id            NUMBER(19)         NOT NULL,
    username      VARCHAR2(50 CHAR)  NOT NULL,
    -- BCrypt 해시는 60자다. 다른 알고리즘으로 바꿀 여유를 두고 100자로 잡는다.
    password_hash VARCHAR2(100 CHAR) NOT NULL,
    created_at    TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_admin_user PRIMARY KEY (id),
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

-- 관리자 계좌 목록 검색용. 예금주 이름 검색은 부분 일치라 인덱스를 못 타지만, 계좌번호는 앞자리 일치라 탄다.
-- (account_number 는 V1 의 유니크 제약이 이미 인덱스를 만든다.)
CREATE INDEX ix_account_status ON account (status, id);
