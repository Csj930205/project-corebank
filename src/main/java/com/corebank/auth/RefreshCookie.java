package com.corebank.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;


/**
 * 리프레시 토큰 쿠키.
 *
 * <ul>
 *   <li><b>HttpOnly</b>: 화면 스크립트(document.cookie)가 읽을 수 없다. XSS 가 생겨도 리프레시 토큰은 못 훔친다
 *       (액세스 토큰은 스크립트가 다뤄야 해서 15분짜리로 짧게 둔다).</li>
 *   <li><b>SameSite=Strict</b>: 다른 사이트에서 시작한 요청에는 쿠키가 실리지 않는다. 쿠키로 인증하는 주소(/auth/refresh,
 *       /auth/logout)를 다른 사이트가 몰래 부르게 하는 CSRF 를 막는다. 나머지 API 는 쿠키가 아니라 Authorization 헤더라
 *       원래 CSRF 대상이 아니다.</li>
 *   <li><b>Path=/auth</b>: 리프레시·로그아웃 요청에만 실린다. 계좌 API 요청마다 오래 사는 토큰을 보낼 이유가 없다.</li>
 *   <li><b>Secure</b>: HTTPS 에서만 보낸다. 브라우저는 http://localhost 를 예외로 안전하다고 봐서 개발 중에도 동작한다.
 *       corebank.auth.refresh-cookie.secure=false 로 끌 수 있다.</li>
 * </ul>
 */
@Component
public class RefreshCookie {

	public static final String NAME = "corebank_refresh";
	static final String PATH = "/auth";

	private final boolean secure;

	public RefreshCookie(@Value("${corebank.auth.refresh-cookie.secure:true}") boolean secure) {
		this.secure = secure;
	}

	/** 새 리프레시 토큰. 쿠키 수명은 토큰 만료와 같다(그 뒤에는 브라우저가 알아서 버린다) */
	ResponseCookie of(AuthSessionService.Issued issued) {
		return base(issued.refreshToken()).maxAge(issued.refreshMaxAge()).build();
	}

	/** 쿠키 지우기(빈 값, 수명 0) */
	ResponseCookie clear() {
		return base("").maxAge(0).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
	}

}
