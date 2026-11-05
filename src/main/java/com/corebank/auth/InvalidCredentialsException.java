package com.corebank.auth;

/**
 * 로그인 실패. 아이디가 틀렸는지 비밀번호가 틀렸는지 구분하지 않는다. ApiExceptionHandler 가 401 로 바꾼다.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("아이디 또는 비밀번호가 올바르지 않습니다.");
	}

}
