package com.corebank.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
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

	/** 서명, 만료 시각(exp), 발급자(iss)를 모두 확인한다. 하나라도 틀리면 401 이다. */
	@Bean
	JwtDecoder jwtDecoder(SecretKey key) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				// 서버 사이 시계 차이를 감안하는 여유 시간. 기본값(60초)보다 짧게 둔다.
				new JwtTimestampValidator(Duration.ofSeconds(10)),
				new JwtIssuerValidator(JwtTokenService.ISSUER)));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
