package com.corebank.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 서명 키와 인코더·디코더, 비밀번호 해시 설정.
 *
 * <p>HS256 은 같은 비밀 키로 서명하고 검증한다. 키가 새면 누구나 관리자 토큰을 만들 수 있으므로
 * 코드에 적지 않고 환경 변수(JWT_SECRET)로만 받는다. HS256 에는 32바이트(256비트) 이상의 키가 필요하다.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	private static final int MIN_SECRET_BYTES = 32;

	@Bean
	SecretKey jwtSecretKey(@Value("${corebank.jwt.secret:}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"JWT_SECRET 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. .env 를 확인하세요. (지금 " + bytes.length + "바이트)");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey key) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	/**
	 * 서명, 만료 시각(exp), 발급자(iss), 로그인 세션(sid)을 모두 확인한다. 하나라도 틀리면 401 이다.
	 *
	 * <p>세션 확인은 요청마다 DB 를 한 번 본다(기본키 조회). 무상태 JWT 의 장점(DB 없이 검증)을 일부 내주는 대신
	 * 로그아웃·도난 탐지·계정 정지를 그 즉시 반영한다. 은행 업무에서는 "끊었는데 15분 더 쓸 수 있다" 가 더 큰 문제다.
	 *
	 * <p>세션 서비스는 ObjectProvider 로 늦게 꺼낸다. 웹 계층 테스트(@WebMvcTest)는 DB 가 없어 그 빈이 없고, 그 테스트들은
	 * 디코더를 거치지 않는 가짜 토큰(jwt())을 쓴다. 빈이 없는데 진짜 토큰이 오면 거부한다(안전한 쪽으로 실패).
	 */
	@Bean
	JwtDecoder jwtDecoder(SecretKey key, ObjectProvider<AuthSessionService> sessions) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		OAuth2TokenValidator<Jwt> activeSession = jwt -> {
			AuthSessionService service = sessions.getIfAvailable();
			String sessionId = jwt.getClaimAsString(JwtTokenService.SESSION_ID_CLAIM);
			return service != null && service.isActive(sessionId)
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(
							new OAuth2Error("invalid_token", "로그인 세션이 끝났거나 없습니다.", null));
		};
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				// 서버 사이 시계 차이를 감안하는 여유 시간. 기본값(60초)보다 짧게 둔다.
				new JwtTimestampValidator(Duration.ofSeconds(10)),
				new JwtIssuerValidator(JwtTokenService.ISSUER),
				// 서명·만료·발급자가 맞는 토큰만 여기까지 온다. 위조 토큰 때문에 DB 를 보지 않는다.
				activeSession));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
