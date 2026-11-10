package com.corebank.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테이블 소유자 계정으로 접속한 JdbcTemplate. 앱 계정에는 없는 권한이 필요한 테스트 준비·조작에만 쓴다.
 *
 * <p>JdbcTemplate 빈을 하나 더 등록하면 스프링이 앱용 JdbcTemplate 을 만들지 않으므로, 이 타입으로 감싸 따로 주입한다.
 */
public record SchemaOwner(JdbcTemplate jdbc) {
}
