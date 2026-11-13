package com.corebank.common;

import java.time.Duration;
import com.corebank.ratelimit.RateLimitExceededException;
import com.corebank.auth.LoginLockedException;
import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.BalanceLimitExceededException;
import com.corebank.account.InsufficientBalanceException;
import com.corebank.account.OwnerNameRequiredException;
import com.corebank.customer.UsernameTakenException;
import com.corebank.admin.AdminAlreadyDecidedException;
import com.corebank.admin.AdminSelfApprovalException;
import com.corebank.admin.AdminUserNotFoundException;
import com.corebank.auth.InvalidCredentialsException;
import com.corebank.closing.BusinessDateNotEndedException;
import com.corebank.closing.ClosingAlreadyRunningException;
import com.corebank.closing.ClosingNotFoundException;
import com.corebank.interest.AccrualAlreadyRunningException;
import com.corebank.interest.MonthNotEndedException;
import com.corebank.interest.SettlementAlreadyRunningException;
import com.corebank.product.ProductNameTakenException;
import com.corebank.product.ProductNotAvailableException;
import com.corebank.product.ProductNotFoundException;
import com.corebank.product.ProductTypeMismatchException;
import com.corebank.account.LinkedTimeDepositExistsException;
import com.corebank.account.TimeDepositRestrictedException;
import com.corebank.timedeposit.TimeDepositNotFoundException;
import com.corebank.correction.CorrectionConflictException;
import com.corebank.correction.CorrectionNotFoundException;
import com.corebank.correction.NotCorrectableException;
import com.corebank.correction.SelfApprovalException;
import com.corebank.correction.TransactionNotFoundException;
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

	/** 로그인 실패가 쌓여 잠김: 429. 언제 다시 시도할 수 있는지 Retry-After(초)로 알려 준다. */
	@ExceptionHandler(LoginLockedException.class)
	public ResponseEntity<ErrorResponse> handleLoginLocked(LoginLockedException e) {
		return tooManyRequests(e.getRetryAfter(), new ErrorResponse("LOGIN_LOCKED",
				"로그인 실패가 너무 많아 잠겼습니다. " + minutes(e.getRetryAfter()) + "분 뒤에 다시 시도하세요."));
	}

	/** 횟수 제한 초과(수취인 조회 등): 429 + Retry-After */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitExceededException e) {
		return tooManyRequests(e.getRetryAfter(), new ErrorResponse("RATE_LIMITED",
				"요청이 너무 많습니다. " + minutes(e.getRetryAfter()) + "분 뒤에 다시 시도하세요."));
	}

	private static ResponseEntity<ErrorResponse> tooManyRequests(Duration retryAfter, ErrorResponse body) {
		// 초 단위로 올림. 0초가 되면 "바로 다시 해도 된다" 는 뜻이 되어 버린다.
		long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
				.body(body);
	}

	private static long minutes(Duration duration) {
		return Math.max(1, (duration.toSeconds() + 59) / 60);
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

	/** 없는 예금 상품: 404 */
	@ExceptionHandler(ProductNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleProductNotFound(ProductNotFoundException e) {
		return new ErrorResponse("PRODUCT_NOT_FOUND", e.getMessage());
	}

	/** 판매 중지된 상품으로 개설, 이미 중지됐거나 기본 상품을 중지: 409 */
	@ExceptionHandler(ProductNotAvailableException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleProductNotAvailable(ProductNotAvailableException e) {
		return new ErrorResponse("PRODUCT_NOT_AVAILABLE", e.getMessage());
	}

	/** 같은 이름의 상품: 409 */
	@ExceptionHandler(ProductTypeMismatchException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleProductTypeMismatch(ProductTypeMismatchException e) {
		return new ErrorResponse("PRODUCT_TYPE_MISMATCH", e.getMessage());
	}

	/** 정기예금 계좌에 입출금·이체·해지·정정. 중도해지로만 돈을 뺄 수 있다 */
	@ExceptionHandler(TimeDepositRestrictedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleTimeDepositRestricted(TimeDepositRestrictedException e) {
		return new ErrorResponse("TIME_DEPOSIT_RESTRICTED", e.getMessage());
	}

	/** 가입 중인 정기예금의 연결계좌 해지 */
	@ExceptionHandler(LinkedTimeDepositExistsException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleLinkedTimeDeposit(LinkedTimeDepositExistsException e) {
		return new ErrorResponse("LINKED_TIME_DEPOSIT_EXISTS", e.getMessage());
	}

	@ExceptionHandler(TimeDepositNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleTimeDepositNotFound(TimeDepositNotFoundException e) {
		return new ErrorResponse("TIME_DEPOSIT_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(ProductNameTakenException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleProductNameTaken(ProductNameTakenException e) {
		return new ErrorResponse("PRODUCT_NAME_TAKEN", e.getMessage());
	}

	/** 이번 달이나 미래 달 결산: 422 */
	@ExceptionHandler(MonthNotEndedException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleMonthNotEnded(MonthNotEndedException e) {
		return new ErrorResponse("MONTH_NOT_ENDED", e.getMessage());
	}

	/** 같은 달 이자 결산 배치가 이미 실행 중: 409 */
	@ExceptionHandler(SettlementAlreadyRunningException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleSettlementRunning(SettlementAlreadyRunningException e) {
		return new ErrorResponse("SETTLEMENT_ALREADY_RUNNING", e.getMessage());
	}

	/** 같은 날 이자 적립이 동시에 실행됨: 409 */
	@ExceptionHandler(AccrualAlreadyRunningException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleAccrualRunning(AccrualAlreadyRunningException e) {
		return new ErrorResponse("ACCRUAL_ALREADY_RUNNING", e.getMessage());
	}

	/** 없는 정정 요청: 404 */
	@ExceptionHandler(CorrectionNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleCorrectionNotFound(CorrectionNotFoundException e) {
		return new ErrorResponse("CORRECTION_NOT_FOUND", e.getMessage());
	}

	/** 정정하려는 원장 거래가 없음: 404 */
	@ExceptionHandler(TransactionNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleTransactionNotFound(TransactionNotFoundException e) {
		return new ErrorResponse("TRANSACTION_NOT_FOUND", e.getMessage());
	}

	/** 정정할 수 없는 거래(해지 지급, 역분개): 422 */
	@ExceptionHandler(NotCorrectableException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleNotCorrectable(NotCorrectableException e) {
		return new ErrorResponse("NOT_CORRECTABLE", e.getMessage());
	}

	/** 이미 정정됨, 승인 대기 요청이 있음, 이미 결정됨: 409. 원인은 코드로 구분한다. */
	@ExceptionHandler(CorrectionConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleCorrectionConflict(CorrectionConflictException e) {
		return new ErrorResponse(e.getCode(), e.getMessage());
	}

	/** 요청한 관리자가 스스로 결정: 403. 관리자 권한은 있지만 이 요청에 대해서는 결정할 수 없다. */
	@ExceptionHandler(SelfApprovalException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleSelfApproval(SelfApprovalException e) {
		return new ErrorResponse("SELF_APPROVAL_NOT_ALLOWED", e.getMessage());
	}

	/** 없는 관리자: 404 */
	@ExceptionHandler(AdminUserNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleAdminNotFound(AdminUserNotFoundException e) {
		return new ErrorResponse("ADMIN_NOT_FOUND", e.getMessage());
	}

	/** 이미 승인·반려된 관리자 등록: 409 */
	@ExceptionHandler(AdminAlreadyDecidedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleAdminAlreadyDecided(AdminAlreadyDecidedException e) {
		return new ErrorResponse("ADMIN_ALREADY_DECIDED", e.getMessage());
	}

	/** 등록을 요청한 관리자가 스스로 결정: 403. 정정의 자기 승인과 같은 코드다. */
	@ExceptionHandler(AdminSelfApprovalException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleAdminSelfApproval(AdminSelfApprovalException e) {
		return new ErrorResponse("SELF_APPROVAL_NOT_ALLOWED", e.getMessage());
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

	/** 조회 기간이 0001-01-01 ~ 9999-12-31 을 벗어남: 400. 그대로 DB 에 넘기면 Oracle 이 거절해 500 이 된다. */
	@ExceptionHandler(InvalidDateRangeException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidDateRange(InvalidDateRangeException e) {
		return new ErrorResponse("INVALID_REQUEST", e.getMessage());
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
