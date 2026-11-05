package com.corebank.audit;

import com.corebank.auth.Actor;
import com.corebank.common.DbTime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * API 요청마다 감사 기록을 한 줄 남긴다.
 *
 * <p>보안 필터 체인 안, 토큰 확인(BearerTokenAuthenticationFilter) 바로 앞에 둔다(SecurityConfig).
 * <ul>
 *   <li>토큰 확인보다 앞이라, 무효한 토큰으로 401 을 받은 요청도 남는다.</li>
 *   <li>체인 안이라, 요청이 끝난 시점에도 SecurityContext 에 누가 요청했는지 남아 있다.
 *       (바깥의 SecurityContextHolderFilter 가 이 필터가 끝난 뒤에 지운다.)</li>
 * </ul>
 *
 * <p>업무 트랜잭션이 끝난 뒤에 쓰므로, 업무가 롤백돼도 감사 기록은 남는다. 반대로 감사 기록 쓰기가 실패해도 이미 처리된
 * 업무를 되돌리지는 않는다. 그때는 같은 내용을 ERROR 로그로 남겨 기록이 사라지지 않게 한다(README 알려진 한계 참고).
 */
public class AuditLogFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	private static final Logger log = LoggerFactory.getLogger(AuditLogFilter.class);

	private final AuditLogRepository repository;

	public AuditLogFilter(AuditLogRepository repository) {
		this.repository = repository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		Optional<AuditAction.Match> match = AuditAction.match(request.getMethod(), path);
		if (match.isEmpty()) {
			chain.doFilter(request, response);
			return;
		}

		String requestId = UUID.randomUUID().toString();
		response.setHeader(REQUEST_ID_HEADER, requestId);
		LocalDateTime occurredAt = DbTime.now();
		long started = System.nanoTime();
		Integer failedStatus = null;
		try {
			chain.doFilter(request, response);
		} catch (IOException | ServletException | RuntimeException e) {
			// 처리하지 못한 예외는 이 뒤에서 500 이 된다. 지금 응답 상태는 아직 200 일 수 있어 직접 정한다.
			failedStatus = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			throw e;
		} finally {
			long durationMs = (System.nanoTime() - started) / 1_000_000;
			int status = failedStatus != null ? failedStatus : response.getStatus();
			write(entry(request, path, match.get(), requestId, occurredAt, status, failedStatus != null, durationMs));
		}
	}

	private AuditLogEntry entry(HttpServletRequest request, String path, AuditAction.Match match, String requestId,
			LocalDateTime occurredAt, int status, boolean unhandled, long durationMs) {
		AuditLogEntry.ActorType actorType = AuditLogEntry.ActorType.ANONYMOUS;
		String username = (String) request.getAttribute(AuditContext.ATTEMPTED_USERNAME);
		Long customerId = null;
		Actor actor = currentActor();
		if (actor != null) {
			actorType = actor.isAdmin() ? AuditLogEntry.ActorType.ADMIN : AuditLogEntry.ActorType.CUSTOMER;
			username = actor.username();
			customerId = actor.customerId();
		}

		Long target = match.accountId();
		Object bodyTarget = request.getAttribute(AuditContext.TARGET_ACCOUNT);
		if (target == null && bodyTarget instanceof Long id) {
			target = id;
		}
		Long counterpart = request.getAttribute(AuditContext.COUNTERPART_ACCOUNT) instanceof Long id ? id : null;

		String errorCode = unhandled ? "INTERNAL_ERROR" : (String) request.getAttribute(AuditContext.ERROR_CODE);
		String query = request.getQueryString();

		return new AuditLogEntry(null, occurredAt, requestId, actorType, username, customerId, match.action(),
				request.getMethod(), query == null ? path : path + "?" + query, status, AuditLogEntry.Outcome.of(status),
				errorCode, target, counterpart, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
				durationMs);
	}

	/** 토큰이 확인된 요청이면 그 주인. 토큰이 없거나 무효하면 null. */
	private static Actor currentActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			return null;
		}
		try {
			return Actor.from(jwt);
		} catch (AccessDeniedException e) {
			// 서명은 맞지만 우리가 발급하지 않는 형태의 토큰(403). 누구인지 정할 수 없다.
			return null;
		}
	}

	private void write(AuditLogEntry entry) {
		try {
			repository.insert(entry);
		} catch (RuntimeException e) {
			log.error("감사 로그를 저장하지 못했습니다. 내용: {}", entry, e);
		}
	}

}
