package com.corebank.auth;

/** 리프레시 토큰이 없거나, 만료됐거나, 세션이 끊겼거나 끝났다. 다시 로그인해야 한다. 401 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("다시 로그인해 주세요.");
	}

}
