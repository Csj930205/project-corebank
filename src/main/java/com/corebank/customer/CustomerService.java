package com.corebank.customer;

import com.corebank.admin.AdminUserRepository;
import com.corebank.auth.InvalidCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 고객 가입과 로그인 확인. 토큰 발급은 호출하는 쪽(AuthController)이 한다.
 */
@Service
public class CustomerService {

	private final CustomerRepository repository;
	private final AdminUserRepository adminUserRepository;
	private final PasswordEncoder passwordEncoder;
	// 없는 아이디일 때도 비밀번호 비교를 한 번 해서, 응답 시간으로 아이디 존재 여부를 알아내지 못하게 한다(관리자 로그인과 같다).
	private final String dummyHash;

	public CustomerService(CustomerRepository repository, AdminUserRepository adminUserRepository,
			PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.adminUserRepository = adminUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	/**
	 * 가입한다. 아이디 중복은 미리 조회하지 않고 DB 유니크 제약으로 확인한다. 조회 후 저장하면 동시에 같은 아이디로
	 * 가입할 때 둘 다 조회를 통과하기 때문이다. saveAndFlush 로 바로 INSERT 해서 이 자리에서 중복을 잡는다.
	 *
	 *
	 * <p>관리자 아이디도 쓸 수 없다. 로그인 창구가 하나라(AuthService) 같은 아이디가 양쪽에 있으면 관리자로만 로그인된다.
	 *
	 * @throws UsernameTakenException 이미 쓰는 아이디(고객 또는 관리자)
	 */
	@Transactional
	public Customer signUp(String username, String password, String name) {
		if (adminUserRepository.findByUsername(username).isPresent()) {
			throw new UsernameTakenException(username);
		}
		try {
			return repository.saveAndFlush(Customer.create(username, passwordEncoder.encode(password), name.strip()));
		} catch (DataIntegrityViolationException e) {
			throw new UsernameTakenException(username);
		}
	}

	/**
	 * @throws InvalidCredentialsException 아이디가 없거나 비밀번호가 틀렸을 때. 어느 쪽인지 알려 주지 않는다.
	 */
	@Transactional(readOnly = true)
	public Customer authenticate(String username, String password) {
		Optional<Customer> customer = repository.findByUsername(username);
		String hash = customer.map(Customer::getPasswordHash).orElse(dummyHash);
		boolean matches = passwordEncoder.matches(password, hash);
		if (customer.isEmpty() || !matches) {
			throw new InvalidCredentialsException();
		}
		return customer.get();
	}

	@Transactional(readOnly = true)
	public Customer get(Long id) {
		return repository.findById(id)
				.orElseThrow(() -> new IllegalStateException("토큰의 고객이 없습니다. id=" + id));
	}

}
