-- Flyway 마이그레이션 파일이다. 앱이 시작될 때 Flyway가 V1, V2 순서로 한 번씩만 실행하고
-- 실행 이력을 flyway_schema_history 테이블에 남긴다. 이미 실행된 파일을 수정하면 체크섬 오류가 나므로,
-- 스키마를 바꿀 때는 이 파일을 고치지 않고 V2 파일을 새로 추가한다.

-- 계좌의 기본키(id)를 만드는 시퀀스. Account 엔티티의 allocationSize = 1 과 증가값을 맞춘다.
CREATE SEQUENCE account_seq START WITH 1 INCREMENT BY 1 NOCACHE;
-- 계좌번호를 만드는 시퀀스. 시퀀스는 동시에 호출해도 같은 값을 주지 않으므로 번호 중복을 막아 준다.
CREATE SEQUENCE account_number_seq START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE account (
    id             NUMBER(19)         NOT NULL,
    -- 은행 코드 3자리 + 일련번호 10자리 = 13자리. 앞자리 0이 사라지면 안 되므로 숫자가 아닌 문자로 저장한다.
    account_number VARCHAR2(13 CHAR)  NOT NULL,
    -- CHAR 단위로 지정해야 한글도 바이트 수와 상관없이 100글자까지 들어간다.
    owner_name     VARCHAR2(100 CHAR) NOT NULL,
    -- 금액은 오차가 없도록 원 단위 정수로 저장한다. Java 의 long 과 대응된다.
    balance        NUMBER(19)         DEFAULT 0 NOT NULL,
    status         VARCHAR2(10 CHAR)  NOT NULL,
    created_at     TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT uk_account_number UNIQUE (account_number),
    -- 애플리케이션 코드에 버그가 있어도 DB가 마지막으로 음수 잔액과 잘못된 상태를 막는다.
    CONSTRAINT ck_account_balance CHECK (balance >= 0),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);
