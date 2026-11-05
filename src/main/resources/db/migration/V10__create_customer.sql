-- 고객 계정과 계좌 소유자.
-- 고객은 스스로 가입하고(아이디, 비밀번호, 이름), 본인 계좌만 조회하고 거래할 수 있다.
-- 관리자(admin_user)와는 다른 테이블이다. 권한도, 계정 관리 방식도 다르기 때문이다.

CREATE SEQUENCE customer_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE customer (
    id            NUMBER(19)         NOT NULL,
    -- 로그인 아이디. 영문 소문자로 시작하고 영문 소문자, 숫자, 밑줄만 쓴다(서비스에서 검사).
    username      VARCHAR2(30 CHAR)  NOT NULL,
    -- BCrypt 해시만 저장한다(V7 관리자와 같은 규칙).
    password_hash VARCHAR2(100 CHAR) NOT NULL,
    -- 실명. 고객이 계좌를 열면 예금주 이름이 된다.
    name          VARCHAR2(100 CHAR) NOT NULL,
    created_at    TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_customer PRIMARY KEY (id),
    -- 같은 아이디로 동시에 가입해도 하나만 통과한다.
    CONSTRAINT uk_customer_username UNIQUE (username)
);

-- 계좌의 주인. 이 기능 전에 만든 계좌는 주인이 없고(null), 관리자만 다룰 수 있다.
-- 한 번 정한 주인은 바꾸지 않는다(명의 변경은 해지 후 새로 개설).
ALTER TABLE account ADD (customer_id NUMBER(19));
ALTER TABLE account ADD CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customer (id);
-- "내 계좌 목록" 과 소유자 확인에 쓴다. 외래키 락 문제도 막는다(V2 주석 참고).
CREATE INDEX ix_account_customer ON account (customer_id, id);
