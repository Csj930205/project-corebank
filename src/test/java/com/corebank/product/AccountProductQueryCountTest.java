package com.corebank.product;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.Account;
import com.corebank.account.AccountResponse;
import com.corebank.account.AccountService;
import com.corebank.admin.AccountSearch;
import com.corebank.admin.AdminQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 → 상품(@ManyToOne LAZY) 의 N+1 확인. Hibernate Statistics 로 실제 나간 SQL 문 수를 센다.
 *
 * <p>트랜잭션 밖에서 응답(AccountResponse)을 만든다. 목록 쿼리가 상품을 함께 읽지 않았다면 상품 이름을 읽는 순간
 * LazyInitializationException 이 나고(세션이 닫혀 프록시를 채울 수 없다), 웹 요청에서는(OSIV 로 세션이 열려 있어)
 * 예외 대신 계좌마다 SELECT 가 한 번씩 더 나간다. @EntityGraph 를 빼 보면 이 테스트가 깨진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountProductQueryCountTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private AdminQueryService adminQueryService;

	@Autowired
	private ProductService productService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void adminSearchLoadsProductsInOneQuery() {
		String tag = "n1-" + UUID.randomUUID().toString().substring(0, 8);
		for (int i = 0; i < 5; i++) {
			DepositProduct product = productService.create(tag + "-상품" + i, new BigDecimal("1.0" + i));
			accountService.open(tag + "-" + i, product.getId());
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		List<Account> accounts = adminQueryService.searchAccounts(new AccountSearch(tag, null), 0, 20).getContent();
		List<AccountResponse> responses = accounts.stream().map(AccountResponse::from).toList();

		assertThat(responses).hasSize(5);
		assertThat(responses).extracting(r -> r.product().name()).allMatch(name -> name.startsWith(tag));
		// 목록(계좌 JOIN 상품) 1번뿐이다. 상품을 따로 읽었다면 5번이 더 나간다.
		// COUNT 도 나가지 않는다: 첫 쪽에 쪽 크기(20)보다 적게 왔으면 그 수가 곧 전체 건수라 Spring Data 가 생략한다.
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

}
