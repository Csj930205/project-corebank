package com.corebank.admin;

import com.corebank.customer.CustomerRepository;
import com.corebank.customer.UsernameTakenException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자 계정 등록과 목록. 거래 정정은 요청한 사람과 다른 관리자가 승인해야 하므로 관리자가 두 명 이상 필요하다.
 *
 * <p>첫 관리자는 서버 시작 때 .env 로 만들고(AdminBootstrap), 그 뒤는 관리자가 등록한다.
 */
@Service
public class AdminUserService {

	private final AdminUserRepository repository;
	private final CustomerRepository customerRepository;
	private final PasswordEncoder passwordEncoder;

	public AdminUserService(AdminUserRepository repository, CustomerRepository customerRepository,
			PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.customerRepository = customerRepository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 관리자를 등록한다. 고객 아이디와도 겹치면 안 된다. 로그인 창구가 하나라(AuthService) 겹치면 고객이 관리자로 로그인된다.
	 * 관리자 아이디 중복은 고객 가입과 같이 DB 유니크 제약으로 확인한다.
	 *
	 * @throws UsernameTakenException 이미 쓰는 아이디(관리자 또는 고객)
	 */
	@Transactional
	public AdminUser create(String username, String password) {
		if (customerRepository.findByUsername(username).isPresent()) {
			throw new UsernameTakenException(username);
		}
		try {
			return repository.saveAndFlush(AdminUser.create(username, passwordEncoder.encode(password)));
		} catch (DataIntegrityViolationException e) {
			throw new UsernameTakenException(username);
		}
	}

	@Transactional(readOnly = true)
	public List<AdminUser> list() {
		return repository.findAll(Sort.by("id"));
	}

}
