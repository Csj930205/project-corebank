package com.corebank.customer;

/** 이미 쓰는 아이디로 가입. ApiExceptionHandler 가 409 로 바꾼다. */
public class UsernameTakenException extends RuntimeException {

	public UsernameTakenException(String username) {
		super("이미 사용 중인 아이디입니다: " + username);
	}

}
