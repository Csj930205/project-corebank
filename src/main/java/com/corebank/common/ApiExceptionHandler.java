package com.corebank.common;

import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.BalanceLimitExceededException;
import com.corebank.account.InsufficientBalanceException;
import com.corebank.account.OwnerNameRequiredException;
import com.corebank.customer.UsernameTakenException;
import com.corebank.auth.InvalidCredentialsException;
import com.corebank.closing.BusinessDateNotEndedException;
import com.corebank.closing.ClosingAlreadyRunningException;
import com.corebank.closing.ClosingNotFoundException;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotencyKeyReusedException;
import com.corebank.idempotency.InvalidIdempotencyKeyException;
import com.corebank.transfer.SameAccountTransferException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러에서 발생한 예외를 한곳에서 잡아 공통 에러 응답으로 바꾼다.
 * 덕분에 컨트롤러마다 try-catch 를 쓰지 않아도 된다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	/** 없는 계좌 조회: 404 */
	@ExceptionHandler(AccountNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleAccountNotFound(AccountNotFoundException e) {
		return new ErrorResponse("ACCOUNT_NOT_FOUND", e.getMessage());
	}

	/** 해지 계좌 거래: 409. 요청 형식은 맞지만 계좌의 현재 상태와 충돌한다. */
	@ExceptionHandler(AccountClosedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleAccountClosed(AccountClosedException e) {
		return new ErrorResponse("ACCOUNT_CLOSED", e.getMessage());
	}

	/** 잔액 한도 초과: 422 */
	@ExceptionHandler(BalanceLimitExceededException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleBalanceLimitExceeded(BalanceLimitExceededException e) {
		return new ErrorResponse("BALANCE_LIMIT_EXCEEDED", e.getMessage());
	}

	/** 잔액 부족: 422. 요청 형식은 맞지만 업무 규칙상 처리할 수 없다. */
	@ExceptionHandler(InsufficientBalanceException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleInsufficientBalance(InsufficientBalanceException e) {
		return new ErrorResponse("INSUFFICIENT_BALANCE", e.getMessage());
	}

	/** 같은 계좌로 이체: 400. 요청 자체가 의미 없는 값이다. */
	@ExceptionHandler(SameAccountTransferException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleSameAccountTransfer(SameAccountTransferException e) {
		return new ErrorResponse("SAME_ACCOUNT_TRANSFER", e.getMessage());
	}

	/** 로그인 실패: 401. 아이디와 비밀번호 중 무엇이 틀렸는지 알려 주지 않는다. */
	@ExceptionHandler(InvalidCredentialsException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleInvalidCredentials(InvalidCredentialsException e) {
		return new ErrorResponse("INVALID_CREDENTIALS", e.getMessage());
	}

	/** 없는 마감 실행: 404 */
	@ExceptionHandler(ClosingNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleClosingNotFound(ClosingNotFoundException e) {
		return new ErrorResponse("CLOSING_NOT_FOUND", e.getMessage());
	}

	/** 같은 영업일 마감이 실행 중: 409 */
	@ExceptionHandler(ClosingAlreadyRunningException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleClosingAlreadyRunning(ClosingAlreadyRunningException e) {
		return new ErrorResponse("CLOSING_ALREADY_RUNNING", e.getMessage());
	}

	/** 오늘이나 미래를 마감: 422. 형식은 맞지만 아직 끝나지 않은 날이라 처리할 수 없다. */
	@ExceptionHandler(BusinessDateNotEndedException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleBusinessDateNotEnded(BusinessDateNotEndedException e) {
		return new ErrorResponse("BUSINESS_DATE_NOT_ENDED", e.getMessage());
	}

	/** 이미 쓰는 아이디로 가입: 409 */
	@ExceptionHandler(UsernameTakenException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleUsernameTaken(UsernameTakenException e) {
		return new ErrorResponse("USERNAME_TAKEN", "이미 사용 중인 아이디입니다.");
	}

	/** 관리자가 예금주 이름 없이 계좌 개설: 400. @Valid 검증 실패와 같은 코드로 응답한다. */
	@ExceptionHandler(OwnerNameRequiredException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleOwnerNameRequired(OwnerNameRequiredException e) {
		return new ErrorResponse("INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
	}

	/** 멱등성 키 형식 오류: 400 */
	@ExceptionHandler(InvalidIdempotencyKeyException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidIdempotencyKey(InvalidIdempotencyKeyException e) {
		return new ErrorResponse("INVALID_IDEMPOTENCY_KEY", e.getMessage());
	}

	/** 필수 헤더 누락: 400. 지금 필수 헤더는 Idempotency-Key 뿐이지만, 다른 헤더가 생겨도 구분되게 이름을 확인한다. */
	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleMissingHeader(MissingRequestHeaderException e) {
		if (IdempotencyKey.HEADER.equalsIgnoreCase(e.getHeaderName())) {
			return new ErrorResponse("IDEMPOTENCY_KEY_MISSING", "Idempotency-Key 헤더가 필요합니다.");
		}
		return new ErrorResponse("INVALID_REQUEST", "필수 헤더가 없습니다: " + e.getHeaderName());
	}

	/** 같은 키로 다른 요청: 422. 형식은 맞지만 이미 다른 요청에 쓴 키라 처리할 수 없다. */
	@ExceptionHandler(IdempotencyKeyReusedException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
		return new ErrorResponse("IDEMPOTENCY_KEY_REUSED", e.getMessage());
	}

	/** @Valid 검증 실패, 쿼리 파라미터 검증 실패(size=0), 읽을 수 없는 JSON, 잘못된 경로 값(/accounts/abc): 400 */
	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HandlerMethodValidationException.class,
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class
	})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest(Exception e) {
		return new ErrorResponse("INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
	}

	/*
	 * 아래 3개는 컨트롤러 메서드에 도달하기 전, 스프링 MVC 가 요청을 연결하는 단계에서 나는 예외다.
	 * 처리기가 없으면 스프링 기본 형식({timestamp, status, error, path})으로 응답하므로 여기서 공통 형식으로 바꾼다.
	 */

	/** 없는 경로(/no-such-path): 404. 계좌가 없는 경우(ACCOUNT_NOT_FOUND)와 구분되도록 코드를 따로 둔다. */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNoResource(NoResourceFoundException e) {
		return new ErrorResponse("RESOURCE_NOT_FOUND", "요청한 경로를 찾을 수 없습니다.");
	}

	/** 지원하지 않는 메서드(DELETE /accounts/1): 405. HTTP 규약대로 Allow 헤더에 사용 가능한 메서드를 알려 준다. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		HttpHeaders headers = new HttpHeaders();
		if (e.getSupportedHttpMethods() != null) {
			headers.setAllow(e.getSupportedHttpMethods());
		}
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.headers(headers)
				.body(new ErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다: " + e.getMethod()));
	}

	/** JSON 이 아닌 Content-Type(text/plain 등): 415. Accept 헤더에 받을 수 있는 형식을 알려 준다. */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(e.getSupportedMediaTypes());
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.headers(headers)
				.body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "Content-Type 은 application/json 이어야 합니다."));
	}

}
