package com.corebank.auth;

import com.corebank.admin.AdminUser;
import com.corebank.admin.AdminUserRepository;
import com.corebank.customer.Customer;
import com.corebank.customer.CustomerService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 로그인. 아이디와 비밀번호를 확인하고 로그인 세션을 연다(액세스 토큰 + 리프레시 토큰, AuthSessionService).
 *
 * <p>로그인 창구는 하나다. 아이디가 관리자 계정이면 관리자로, 아니면 고객으로 확인하고, 어느 쪽인지는 토큰의 role 로
 * 알려 준다. 고객 가입은 관리자 아이디를 쓸 수 없게 막으므로(CustomerService) 한 아이디가 양쪽에 있는 일은 없다.
 */
@Service
public class AuthService {

	private final AdminUserRepository adminUserRepository;
	private final CustomerService customerService;
	private final PasswordEncoder passwordEncoder;
	private final AuthSessionService sessions;
	private final LoginThrottle throttle;

	public AuthService(AdminUserRepository adminUserRepository, CustomerService customerService,
			PasswordEncoder passwordEncoder, AuthSessionService sessions, LoginThrottle throttle) {
		this.adminUserRepository = adminUserRepository;
		this.customerService = customerService;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.throttle = throttle;
	}

	/**
	 * 관리자 또는 고객으로 로그인한다. 어느 경로든 비밀번호 비교는 한 번이라, 응답 시간으로 아이디가 관리자인지
	 * 고객인지 없는 아이디인지 알아낼 수 없다(없는 아이디는 CustomerService 가 가짜 해시와 비교한다).
	 *
	 * <p>연속 실패가 쌓이면 아이디를 잠시 잠근다(LoginThrottle). 관리자 아이디도, 없는 아이디도 똑같이 센다.
	 *
	 * @throws InvalidCredentialsException 아이디가 없거나 비밀번호가 틀렸을 때. 어느 쪽인지 알려 주지 않는다.
	 * @throws LoginLockedException        실패가 쌓여 잠긴 아이디. 비밀번호를 확인하지 않는다.
	 */
	public AuthSessionService.Issued login(String username, String password) {
		throttle.acquire(username);
		Optional<AdminUser> admin = adminUserRepository.findByUsername(username);
		AuthSessionService.Issued result;
		if (admin.isPresent()) {
			// 승인 전·반려된 계정도 비밀번호를 먼저 비교한다. 상태부터 보면 응답이 빨라 "있는 아이디"임이 드러난다.
			// 실패 응답도 비밀번호가 틀렸을 때와 똑같이 준다(승인 대기인지 알려 주면 아이디가 있다는 것도 알려 주는 셈이다).
			if (!passwordEncoder.matches(password, admin.get().getPasswordHash()) || !admin.get().isActive()) {
				throw new InvalidCredentialsException();
			}
			result = sessions.issueAdminToken(admin.get().getUsername(), admin.get().getRole());
		} else {
			result = customerToken(customerService.authenticate(username, password));
		}
		throttle.succeeded(username);
		return result;
	}

	/** 고객으로만 로그인한다. 관리자 계정은 여기서 로그인되지 않는다. 실패 횟수는 login 과 함께 센다. */
	public AuthSessionService.Issued customerLogin(String username, String password) {
		throttle.acquire(username);
		AuthSessionService.Issued result = customerToken(customerService.authenticate(username, password));
		throttle.succeeded(username);
		return result;
	}

	/** 가입 직후처럼 이미 확인된 고객에게 토큰을 준다. */
	public AuthSessionService.Issued customerToken(Customer customer) {
		return sessions.issueCustomerToken(customer.getId(), customer.getUsername(), customer.getName());
	}

}
