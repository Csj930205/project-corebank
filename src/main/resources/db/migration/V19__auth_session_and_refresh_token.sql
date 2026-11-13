-- 로그인 세션과 리프레시 토큰.
--
-- 지금까지는 서버가 토큰을 저장하지 않아(무상태 JWT) 발급한 토큰을 만료 전에 끊을 수 없었다. 로그아웃은 화면이 토큰을 버리는 것뿐이었다.
-- 이제 로그인 한 번 = 세션 한 줄(auth_session)이다.
--   - 액세스 토큰(JWT, 15분)에 세션 번호(sid)를 넣고, 요청마다 세션이 살아 있는지 본다. 로그아웃하면 그 즉시 401 이다.
--   - 리프레시 토큰(무작위 문자열, HttpOnly 쿠키)으로 새 액세스 토큰을 받는다. 쓸 때마다 새 것으로 바꾸고(회전), 쓴 것을
--     다시 내밀면 도난으로 보고 세션을 끊는다.
--   - 오래 쓰지 않으면(30분) 끝나고, 계속 써도 로그인 뒤 12시간이면 끝난다(다시 로그인).

CREATE TABLE auth_session (
    -- 세션 번호. 액세스 토큰의 sid claim. 순번이면 남의 세션 번호를 짐작할 수 있어 무작위(UUID)다.
    id                VARCHAR2(36 CHAR) NOT NULL,
    principal_type    VARCHAR2(10 CHAR) NOT NULL,
    username          VARCHAR2(50 CHAR) NOT NULL,
    -- 고객 세션만. 관리자는 null
    customer_id       NUMBER(19),
    created_at        TIMESTAMP(6)      NOT NULL,
    -- 절대 만료. 이 시각이 지나면 리프레시해도 안 되고 다시 로그인해야 한다
    expires_at        TIMESTAMP(6)      NOT NULL,
    last_refreshed_at TIMESTAMP(6),
    revoked_at        TIMESTAMP(6),
    -- LOGOUT(로그아웃), REUSE_DETECTED(쓴 리프레시 토큰을 다시 씀), ACCOUNT_INACTIVE(계정이 더는 로그인할 수 없음)
    revoke_reason     VARCHAR2(20 CHAR),
    CONSTRAINT pk_auth_session PRIMARY KEY (id),
    CONSTRAINT ck_auth_session_type CHECK (
        (principal_type = 'ADMIN' AND customer_id IS NULL)
        OR (principal_type = 'CUSTOMER' AND customer_id IS NOT NULL)
    ),
    CONSTRAINT ck_auth_session_period CHECK (created_at < expires_at),
    CONSTRAINT ck_auth_session_revoked CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IN ('LOGOUT', 'REUSE_DETECTED', 'ACCOUNT_INACTIVE'))
    )
);
-- 한 사용자의 세션 목록(관리자 정지 때 모두 끊기 등)
CREATE INDEX ix_auth_session_user ON auth_session (principal_type, username);

CREATE SEQUENCE refresh_token_seq START WITH 1 INCREMENT BY 1 NOCACHE;

-- 리프레시 토큰. 원문은 저장하지 않고 SHA-256 해시만 둔다. DB 가 새도 해시로는 토큰을 만들 수 없다.
-- (비밀번호처럼 bcrypt 를 쓰지 않는 이유: 토큰은 256비트 무작위라 사전 공격이 불가능하고, 해시로 바로 찾아야 해서
--  같은 입력에 같은 결과가 나오는 해시가 필요하다. bcrypt 는 소금 때문에 값으로 찾을 수 없다.)
CREATE TABLE refresh_token (
    id         NUMBER(19)        NOT NULL,
    session_id VARCHAR2(36 CHAR) NOT NULL,
    token_hash VARCHAR2(64 CHAR) NOT NULL,
    created_at TIMESTAMP(6)      NOT NULL,
    -- 이 토큰의 만료(쓰지 않고 30분). 세션의 절대 만료보다 늦을 수 없다
    expires_at TIMESTAMP(6)      NOT NULL,
    -- 새 토큰으로 바꾼 시각. 값이 있으면 이미 쓴 토큰이다
    used_at    TIMESTAMP(6),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id) REFERENCES auth_session (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_period CHECK (created_at < expires_at)
);
CREATE INDEX ix_refresh_token_session ON refresh_token (session_id);
