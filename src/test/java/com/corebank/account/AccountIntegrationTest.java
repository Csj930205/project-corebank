package com.corebank.account;

import com.corebank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 통합 테스트. 실제 Oracle 컨테이너에 Flyway 로 테이블을 만들고 서비스부터 DB까지 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountIntegrationTest {

	@Autowired
	private AccountService accountService;

	/** 개설한 계좌가 DB에 저장되고, 초기 잔액 0과 ACTIVE 상태로 조회된다. */
	@Test
	void openedAccountCanBeReadFromOracle() {
		Account opened = accountService.open("홍길동");

		Account found = accountService.get(opened.getId());

		assertThat(found.getAccountNumber()).isEqualTo(opened.getAccountNumber()).startsWith("110").hasSize(13);
		assertThat(found.getOwnerName()).isEqualTo("홍길동");
		assertThat(found.getBalance()).isZero();
		assertThat(found.getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	/** 계좌마다 다른 번호를 받는다. */
	@Test
	void eachAccountGetsADifferentNumber() {
		Account first = accountService.open("김철수");
		Account second = accountService.open("이영희");

		assertThat(first.getAccountNumber()).isNotEqualTo(second.getAccountNumber());
	}

	/** 없는 계좌를 조회하면 업무 예외가 발생한다. */
	@Test
	void missingAccountThrows() {
		assertThatThrownBy(() -> accountService.get(-1L))
				.isInstanceOf(AccountNotFoundException.class);
	}

}
