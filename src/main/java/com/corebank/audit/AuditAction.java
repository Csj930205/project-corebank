package com.corebank.audit;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Map;
import java.util.Optional;

/**
 * 감사 로그에 남기는 요청 종류. 여기에 없는 요청(GET /auth/me, 없는 경로 등)은 남기지 않는다.
 *
 * <p>컨트롤러 매핑이 아니라 경로 패턴으로 직접 맞춘다. 보안 필터가 401/403 으로 막은 요청은 컨트롤러까지 가지 않아
 * 스프링이 매핑 정보를 만들지 않는데, 이런 거부된 시도야말로 남겨야 하기 때문이다.
 *
 * <p>위에서부터 처음 맞는 것을 쓴다. /accounts/lookup 은 /accounts/{id} 보다 먼저 와야 한다.
 */
public enum AuditAction {

	LOGIN("POST", "/auth/login"),
	CUSTOMER_LOGIN("POST", "/auth/customer/login"),
	CUSTOMER_SIGNUP("POST", "/auth/customer/signup"),

	ACCOUNT_OPEN("POST", "/accounts"),
	MY_ACCOUNTS("GET", "/accounts"),
	RECIPIENT_LOOKUP("GET", "/accounts/lookup"),
	ACCOUNT_VIEW("GET", "/accounts/{id}"),
	TRANSACTION_HISTORY("GET", "/accounts/{id}/transactions"),
	DEPOSIT("POST", "/accounts/{id}/deposits"),
	WITHDRAWAL("POST", "/accounts/{id}/withdrawals"),
	ACCOUNT_CLOSE("POST", "/accounts/{id}/closure"),
	TRANSFER("POST", "/transfers"),

	ADMIN_ACCOUNT_SEARCH("GET", "/admin/accounts"),
	ADMIN_SUMMARY("GET", "/admin/summary"),
	CLOSING_RUN("POST", "/admin/closings"),
	CLOSING_LIST("GET", "/admin/closings"),
	CLOSING_VIEW("GET", "/admin/closings/{id}"),
	// 감사 기록을 본 것도 남긴다. 누가 누구의 기록을 찾아봤는지가 다시 감사 대상이다.
	AUDIT_LOG_SEARCH("GET", "/admin/audit-logs");

	private final String method;
	private final PathPattern pattern;

	AuditAction(String method, String pattern) {
		this.method = method;
		this.pattern = PathPatternParser.defaultInstance.parse(pattern);
	}

	/**
	 * @param accountId 경로의 {id} 가 계좌 번호일 때 그 값. 마감 상세(/admin/closings/{id})는 계좌가 아니므로 null.
	 */
	public record Match(AuditAction action, Long accountId) {
	}

	public static Optional<Match> match(String method, String path) {
		PathContainer container = PathContainer.parsePath(path);
		for (AuditAction action : values()) {
			if (!action.method.equalsIgnoreCase(method)) {
				continue;
			}
			PathPattern.PathMatchInfo info = action.pattern.matchAndExtract(container);
			if (info != null) {
				return Optional.of(new Match(action, action.accountIdFrom(info.getUriVariables())));
			}
		}
		return Optional.empty();
	}

	private Long accountIdFrom(Map<String, String> variables) {
		String id = variables.get("id");
		if (id == null || this == CLOSING_VIEW) {
			return null;
		}
		try {
			return Long.valueOf(id);
		} catch (NumberFormatException e) {
			// /accounts/abc 같은 잘못된 요청. 400 으로 끝나고 기록은 남지만 대상 계좌는 없다.
			return null;
		}
	}

}
