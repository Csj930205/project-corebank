package com.corebank.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청을 처리하는 쪽이 감사 기록에 보탤 정보. 필터는 경로와 토큰만 보므로, 본문에 있는 값(로그인 아이디, 이체 계좌)은
 * 컨트롤러가 여기에 적어 둔다. 요청 속성(request attribute)에 담으므로 요청이 끝나면 함께 사라진다.
 *
 * <p>업무 처리 전에 적어야 실패한 요청에도 남는다. 예: 이체는 잔액 부족으로 실패해도 어느 계좌에서 시도했는지 남아야 한다.
 */
public final class AuditContext {

	static final String ATTEMPTED_USERNAME = AuditContext.class.getName() + ".attemptedUsername";
	static final String TARGET_ACCOUNT = AuditContext.class.getName() + ".targetAccount";
	static final String COUNTERPART_ACCOUNT = AuditContext.class.getName() + ".counterpartAccount";
	static final String ERROR_CODE = AuditContext.class.getName() + ".errorCode";

	private AuditContext() {
	}

	/** 로그인·가입에서 시도한 아이디. 토큰이 없는 요청이라 필터가 알 수 없다. */
	public static void attemptedUsername(String username) {
		set(ATTEMPTED_USERNAME, username);
	}

	/** 이체처럼 대상 계좌가 경로가 아니라 본문에 있을 때 */
	public static void accounts(Long target, Long counterpart) {
		set(TARGET_ACCOUNT, target);
		set(COUNTERPART_ACCOUNT, counterpart);
	}

	/** 실패 응답의 에러 코드. 공통 에러 응답을 쓰는 곳(ApiExceptionHandler, 보안 필터)이 적는다. */
	public static void errorCode(String code) {
		set(ERROR_CODE, code);
	}

	/** 보안 필터처럼 요청 객체를 직접 가진 곳에서 쓴다. */
	public static void errorCode(HttpServletRequest request, String code) {
		request.setAttribute(ERROR_CODE, code);
	}

	private static void set(String name, Object value) {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		// 요청 밖(배치, 단위 테스트)에서 불리면 남길 곳이 없으니 넘어간다.
		if (attributes != null && value != null) {
			attributes.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
		}
	}

}
