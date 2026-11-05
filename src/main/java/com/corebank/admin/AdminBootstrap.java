package com.corebank.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 시작할 때 첫 관리자 계정을 만든다. 같은 아이디가 이미 있으면 아무것도 하지 않는다.
 *
 * <p>아이디와 비밀번호는 코드에 적지 않고 환경 변수(.env 의 ADMIN_USERNAME, ADMIN_PASSWORD)에서 읽는다.
 * 비밀번호를 바꿔도 이미 만든 계정에는 반영되지 않는다. 계정은 한 번만 만들기 때문이다.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
	private static final int MIN_PASSWORD_LENGTH = 12;

	private final AdminUserRepository repository;
	private final PasswordEncoder passwordEncoder;
	private final String username;
	private final String password;

	AdminBootstrap(AdminUserRepository repository, PasswordEncoder passwordEncoder,
			@Value("${corebank.admin.username:}") String username,
			@Value("${corebank.admin.password:}") String password) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.username = username;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (username.isBlank() || password.isBlank()) {
			log.warn("ADMIN_USERNAME / ADMIN_PASSWORD 가 없어 관리자 계정을 만들지 않았습니다.");
			return;
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException("ADMIN_PASSWORD 는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
		}
		if (repository.existsByUsername(username)) {
			return;
		}
		repository.save(AdminUser.create(username, passwordEncoder.encode(password)));
		log.info("관리자 계정을 만들었습니다: {}", username);
	}

}
