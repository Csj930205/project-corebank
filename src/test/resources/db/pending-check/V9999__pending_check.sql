-- SchemaVersionCheckIntegrationTest 전용. 적용하지 않은 마이그레이션이 있는 상황을 흉내 낸다. 실행되지 않는다(validate 만 한다).
CREATE TABLE pending_check (id NUMBER(19));
