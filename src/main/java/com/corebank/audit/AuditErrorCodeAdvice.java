package com.corebank.audit;

import com.corebank.common.ErrorResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 실패 응답의 에러 코드(INSUFFICIENT_BALANCE 등)를 감사 기록에 넘긴다. ApiExceptionHandler 의 처리기마다 적지 않고,
 * 응답 본문이 ErrorResponse 일 때 한곳에서 잡는다.
 */
@RestControllerAdvice
public class AuditErrorCodeAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (body instanceof ErrorResponse error) {
			AuditContext.errorCode(error.code());
		}
		return body;
	}

}
