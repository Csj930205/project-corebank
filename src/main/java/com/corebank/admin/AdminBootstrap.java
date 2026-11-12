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
 * 서버가 시작할 때 첫 관리자 두 명을 만든다. 같은 아이디가 이미 있으면 아무것도 하지 않는다.
 *
 * <ul>
 *   <li>운영자(.env 의 ADMIN_USERNAME, ADMIN_PASSWORD): 다른 관리자의 등록을 요청한다.</li>
 *   <li>책임자(.env 의 APPROVER_USERNAME, APPROVER_PASSWORD): 그 요청을 승인한다.</li>
 * </ul>
 *
 * <p>왜 두 명인가: 관리자 등록에는 요청자와 승인자가 따로 있어야 한다. 처음에 한 명뿐이면 아무도 승인할 수 없고,
 * 그렇다고 "처음 한 번은 승인 없이" 같은 예외를 앱에 두면 그 틈으로 혼자 계정을 늘릴 수 있다. 그래서 처음 두 명은
 * 앱 밖(서버 설정)에서 정한다. 서버 설정을 다루는 사람은 원래 DB 소유자 권한도 가진 사람이라, 여기서 새로 생기는 틈은 없다.
 *
 * <p>아이디와 비밀번호는 코드에 적지 않고 환경 변수에서 읽는다. 비밀번호를 바꿔도 이미 만든 계정에는 반영되지 않는다.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
	private static final int MIN_PASSWORD_LENGTH = 12;

	private final AdminUserRepository repository;
	private final PasswordEncoder passwordEncoder;
	private final Seed operator;
	private final Seed approver;

	private record Seed(String username, String password, AdminRole role, String envName) {

		boolean isBlank() {
			return username.isBlank() || password.isBlank();
		}
	}

	AdminBootstrap(AdminUserRepository repository, PasswordEncoder passwordEncoder,
			@Value("${corebank.admin.username:}") String username,
			@Value("${corebank.admin.password:}") String password,
			@Value("${corebank.approver.username:}") String approverUsername,
			@Value("${corebank.approver.password:}") String approverPassword) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.operator = new Seed(username, password, AdminRole.OPERATOR, "ADMIN");
		this.approver = new Seed(approverUsername, approverPassword, AdminRole.APPROVER, "APPROVER");
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!operator.isBlank() && operator.username().equals(approver.username())) {
			throw new IllegalStateException("ADMIN_USERNAME 과 APPROVER_USERNAME 은 달라야 합니다(요청자와 승인자는 다른 사람).");
		}
		create(operator);
		create(approver);
	}

	private void create(Seed seed) {
		if (seed.isBlank()) {
			log.warn("{}_USERNAME / {}_PASSWORD 가 없어 {} 계정을 만들지 않았습니다.", seed.envName(), seed.envName(), seed.role());
			return;
		}
		if (seed.password().length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException(seed.envName() + "_PASSWORD 는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
		}
		if (repository.existsByUsername(seed.username())) {
			return;
		}
		repository.save(AdminUser.bootstrap(seed.username(), passwordEncoder.encode(seed.password()), seed.role()));
		log.info("{} 계정을 만들었습니다: {}", seed.role(), seed.username());
	}

}
