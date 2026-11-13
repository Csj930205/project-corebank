package com.corebank.auth;

/**
 * 이미 쓴 리프레시 토큰이 다시 왔다. 토큰이 새어 나가 두 곳에서 쓰는 것으로 보고 세션을 끊었다. 401
 *
 * <p>이 예외는 트랜잭션을 롤백하지 않는다(AuthSessionService.refresh 의 noRollbackFor). 세션을 끊은 기록이 남아야 하기 때문이다.
 */
public class RefreshTokenReusedException extends RuntimeException {

	public RefreshTokenReusedException() {
		super("이미 사용한 로그인 정보입니다. 보안을 위해 로그아웃했습니다. 다시 로그인해 주세요.");
	}

}
