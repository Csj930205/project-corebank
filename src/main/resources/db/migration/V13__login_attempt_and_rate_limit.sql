-- 횟수 제한. 서버 메모리가 아니라 DB 에 세어, 서버를 여러 대 띄워도 같은 기준으로 막는다.
-- 막힌 요청(429)은 감사 로그에 DENIED 로 남는다(V11 주석의 401/403 에 429 가 더해졌다).

-- 로그인 실패 횟수와 잠금. 아이디(문자열)마다 한 줄.
-- 없는 아이디도 똑같이 세고 잠근다. 없는 아이디만 잠기지 않으면 "잠기는가" 로 계정이 있는지 알아낼 수 있다.
CREATE TABLE login_attempt (
    username      VARCHAR2(50 CHAR) NOT NULL,
    -- 마지막 성공(또는 잠금 해제) 뒤 연속 실패 수. 비밀번호를 확인하기 "전에" 1을 더한다(LoginThrottle 참고).
    failure_count NUMBER(3)         NOT NULL,
    -- 이 시각까지 잠금. null 이면 잠기지 않음
    locked_until  TIMESTAMP(6),
    updated_at    TIMESTAMP(6)      NOT NULL,
    CONSTRAINT pk_login_attempt PRIMARY KEY (username),
    CONSTRAINT ck_login_attempt_count CHECK (failure_count >= 0)
);

-- 고정 구간(예: 1시간) 횟수 제한. 키(무엇을, 누가)와 구간 시작 시각마다 한 줄.
-- 예: limit_key = 'recipient-lookup:customer:42', window_start = 14:00 → 14:00~15:00 사이 조회 수
CREATE TABLE rate_limit (
    limit_key    VARCHAR2(100 CHAR) NOT NULL,
    window_start TIMESTAMP(6)       NOT NULL,
    hit_count    NUMBER(10)         NOT NULL,
    CONSTRAINT pk_rate_limit PRIMARY KEY (limit_key, window_start)
);

-- 지난 구간 정리(RateLimitCleanup)에 쓴다.
CREATE INDEX ix_rate_limit_window ON rate_limit (window_start);
