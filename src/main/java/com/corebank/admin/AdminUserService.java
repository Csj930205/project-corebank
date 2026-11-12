package com.corebank.admin;

import com.corebank.customer.CustomerRepository;
import com.corebank.customer.UsernameTakenException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자 등록과 승인. 운영자(OPERATOR)가 요청하고 책임자(APPROVER)가 승인해야 계정이 생긴다.
 *
 * <p>왜 등록에도 승인이 필요한가: 관리자 한 명이 계정을 바로 만들 수 있으면, 창구 계정과 책임자 계정을 혼자 만들어
 * 자기 정정 요청을 자기가 승인할 수 있다. 계정을 늘리는 일에도 두 사람이 필요해야 거래 정정의 4-eyes 가 진짜가 된다.
 *
 * <p>누가 무엇을 하는지(요청은 운영자, 승인은 책임자)는 SecurityConfig 의 URL 규칙이 정한다. 이 클래스는
 * "요청한 사람이 결정하지 않는다"와 "한 번만 결정한다"만 지킨다.
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
	 * 관리자 등록을 요청한다(PENDING). 고객 아이디와도 겹치면 안 된다. 로그인 창구가 하나라(AuthService) 겹치면
	 * 고객이 관리자로 로그인된다. 반려된 아이디도 계속 쓰는 중으로 본다(누가 언제 반려했는지 기록이 남아야 한다).
	 *
	 * @throws UsernameTakenException 이미 쓰는 아이디(관리자 또는 고객)
	 */
	@Transactional
	public AdminUser request(String username, String password, AdminRole role, String requestedBy) {
		if (customerRepository.findByUsername(username).isPresent()) {
			throw new UsernameTakenException(username);
		}
		try {
			return repository.saveAndFlush(
					AdminUser.requested(username, passwordEncoder.encode(password), role, requestedBy));
		} catch (DataIntegrityViolationException e) {
			throw new UsernameTakenException(username);
		}
	}

	/** 승인. 이제 로그인할 수 있다. */
	@Transactional
	public AdminUser approve(Long id, String approver) {
		AdminUser user = find(id);
		user.approve(approver);
		return flushDecision(user);
	}

	/** 반려. 계정은 남지만 로그인할 수 없다. */
	@Transactional
	public AdminUser reject(Long id, String approver) {
		AdminUser user = find(id);
		user.reject(approver);
		return flushDecision(user);
	}

	/** @param status null 이면 전체 */
	@Transactional(readOnly = true)
	public List<AdminUser> list(AdminUserStatus status) {
		Sort byId = Sort.by("id");
		return status == null ? repository.findAll(byId) : repository.findByStatus(status, byId);
	}

	private AdminUser find(Long id) {
		return repository.findById(id).orElseThrow(() -> new AdminUserNotFoundException(id));
	}

	/**
	 * 결정을 지금 DB 에 보낸다(CorrectionService.flushDecision 과 같은 이유). 두 책임자가 동시에 결정했으면
	 * 나중 쪽은 version 이 맞지 않아 0건이 바뀌고, 그 예외를 여기서 잡아 409 로 바꾼다.
	 */
	private AdminUser flushDecision(AdminUser user) {
		try {
			return repository.saveAndFlush(user);
		} catch (ObjectOptimisticLockingFailureException e) {
			throw new AdminAlreadyDecidedException(user.getId());
		}
	}

}
