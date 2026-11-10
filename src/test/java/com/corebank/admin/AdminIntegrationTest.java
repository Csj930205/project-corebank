package com.corebank.admin;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.Account;
import com.corebank.account.AccountService;
import com.corebank.account.AccountStatus;
import com.corebank.transfer.TransferService;
import com.corebank.common.DateRange;
import com.corebank.support.SchemaOwner;
import com.jayway.jsonpath.JsonPath;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 조회 통합 테스트. 다른 테스트가 같은 DB 에 계좌를 계속 만들기 때문에,
 * 테스트마다 고유한 이름표(tag)를 붙인 계좌를 만들어 그 계좌들만 검색해 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminIntegrationTest {

	@Autowired
	private AdminQueryService adminQueryService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchemaOwner owner;

	@Autowired
	private MockMvc mockMvc;

	private static String tag() {
		return "t" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** 최신 개설순으로, size 씩 나뉘고 전체 건수와 쪽 수가 맞다. */
	@Test
	void listsNewestFirstWithPaging() {
		String tag = tag();
		for (int i = 1; i <= 5; i++) {
			accountService.open(tag + "-" + i);
		}

		Page<Account> first = adminQueryService.searchAccounts(new AccountSearch(tag, null), 0, 2);
		Page<Account> last = adminQueryService.searchAccounts(new AccountSearch(tag, null), 2, 2);

		assertThat(first.getTotalElements()).isEqualTo(5);
		assertThat(first.getTotalPages()).isEqualTo(3);
		assertThat(first.getContent()).extracting(Account::getOwnerName).containsExactly(tag + "-5", tag + "-4");
		assertThat(last.getContent()).extracting(Account::getOwnerName).containsExactly(tag + "-1");
	}

	/** 예금주 이름은 부분 일치, 대소문자 구분 없음 */
	@Test
	void searchesOwnerNamePartially() {
		String tag = tag();
		accountService.open("Kim-" + tag + "-alpha");
		accountService.open("Lee-" + tag + "-beta");

		assertThat(adminQueryService.searchAccounts(new AccountSearch(tag.toUpperCase() + "-ALPHA", null), 0, 20).getContent())
				.extracting(Account::getOwnerName).containsExactly("Kim-" + tag + "-alpha");
		assertThat(adminQueryService.searchAccounts(new AccountSearch(tag, null), 0, 20).getTotalElements()).isEqualTo(2);
	}

	/** 계좌번호는 앞자리 일치 */
	@Test
	void searchesAccountNumberByPrefix() {
		Account account = accountService.open(tag());

		Page<Account> found = adminQueryService.searchAccounts(new AccountSearch(account.getAccountNumber(), null), 0, 20);

		assertThat(found.getContent()).extracting(Account::getId).containsExactly(account.getId());
	}

	/** 상태로 거른다. */
	@Test
	void filtersByStatus() {
		String tag = tag();
		Account active = accountService.open(tag + "-active");
		Account closed = accountService.open(tag + "-closed");
		accountService.close(closed.getId(), newKey());

		assertThat(adminQueryService.searchAccounts(new AccountSearch(tag, AccountStatus.CLOSED), 0, 20).getContent())
				.extracting(Account::getId).containsExactly(closed.getId());
		assertThat(adminQueryService.searchAccounts(new AccountSearch(tag, AccountStatus.ACTIVE), 0, 20).getContent())
				.extracting(Account::getId).containsExactly(active.getId());
	}

	/** 검색어의 % 와 _ 는 와일드카드가 아니라 글자 그대로다. "%" 검색이 모든 계좌와 맞으면 안 된다. */
	@Test
	void likeWildcardsAreEscaped() {
		String tag = tag();
		accountService.open(tag + "_50%할인");
		accountService.open(tag + "X50Y할인");

		assertThat(adminQueryService.searchAccounts(new AccountSearch(tag + "_50%", null), 0, 20).getContent())
				.extracting(Account::getOwnerName).containsExactly(tag + "_50%할인");
		assertThat(AdminQueryService.escapeLike("a%b_c\\")).isEqualTo("a\\%b\\_c\\\\");
	}

	/** 잔액 범위는 양 끝을 포함한다. 한쪽만 줘도 된다. */
	@Test
	void filtersByBalanceRangeInclusive() {
		String tag = tag();
		Account low = accountService.open(tag + "-low");
		Account mid = accountService.open(tag + "-mid");
		Account high = accountService.open(tag + "-high");
		accountService.deposit(low.getId(), 1_000, newKey());
		accountService.deposit(mid.getId(), 5_000, newKey());
		accountService.deposit(high.getId(), 10_000, newKey());

		assertThat(ids(new AccountSearch(tag, null, DateRange.ALL, 1_000L, 5_000L)))
				.containsExactly(mid.getId(), low.getId());
		assertThat(ids(new AccountSearch(tag, null, DateRange.ALL, 5_000L, null)))
				.containsExactly(high.getId(), mid.getId());
		assertThat(ids(new AccountSearch(tag, null, DateRange.ALL, null, 999L))).isEmpty();
		// 시작이 끝보다 크면 에러가 아니라 빈 결과
		assertThat(ids(new AccountSearch(tag, null, DateRange.ALL, 5_000L, 1_000L))).isEmpty();
	}

	/** 개설일 범위는 날짜 단위이고 양 끝 날짜를 하루 종일 포함한다(끝 날짜 23:59:59.999999 도 들어간다). */
	@Test
	void filtersByOpenedDateRangeInclusive() {
		String tag = tag();
		Account before = openedAt(tag + "-before", LocalDateTime.of(2001, 3, 9, 23, 59, 59, 999_999_000));
		Account first = openedAt(tag + "-first", LocalDateTime.of(2001, 3, 10, 0, 0));
		Account last = openedAt(tag + "-last", LocalDateTime.of(2001, 3, 12, 23, 59, 59, 999_999_000));
		Account after = openedAt(tag + "-after", LocalDateTime.of(2001, 3, 13, 0, 0));

		assertThat(ids(opened(tag, LocalDate.of(2001, 3, 10), LocalDate.of(2001, 3, 12))))
				.containsExactly(last.getId(), first.getId());
		assertThat(ids(opened(tag, LocalDate.of(2001, 3, 13), null))).containsExactly(after.getId());
		assertThat(ids(opened(tag, null, LocalDate.of(2001, 3, 9)))).containsExactly(before.getId());
		assertThat(ids(opened(tag, LocalDate.of(2001, 3, 12), LocalDate.of(2001, 3, 10)))).isEmpty();
	}

	/** 개설일과 잔액 조건은 함께(AND) 건다. */
	@Test
	void combinesDateAndBalanceConditions() {
		String tag = tag();
		Account oldRich = openedAt(tag + "-old-rich", LocalDateTime.of(2001, 5, 1, 12, 0));
		openedAt(tag + "-old-poor", LocalDateTime.of(2001, 5, 1, 12, 0));
		Account newRich = accountService.open(tag + "-new-rich");
		accountService.deposit(oldRich.getId(), 50_000, newKey());
		accountService.deposit(newRich.getId(), 50_000, newKey());

		var search = new AccountSearch(tag, null, new DateRange(LocalDate.of(2001, 5, 1), LocalDate.of(2001, 5, 1)),
				10_000L, null);
		assertThat(ids(search)).containsExactly(oldRich.getId());
	}

	/** 요청 값 검증: 음수 잔액, 날짜 형식 오류, DB 가 못 다루는 날짜는 400. 9999-12-31 은 된다. */
	@Test
	void rejectsInvalidRangeParameters() throws Exception {
		String admin = adminToken();
		for (String bad : List.of("minBalance=-1", "maxBalance=abc", "openedFrom=2026-13-01", "openedTo=%2B10000-01-01",
				"openedFrom=0000-12-31")) {
			mockMvc.perform(get("/admin/accounts?" + bad).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		mockMvc.perform(get("/admin/accounts?openedFrom=0001-01-01&openedTo=9999-12-31&minBalance=0&maxBalance="
						+ Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isOk());
	}

	private List<Long> ids(AccountSearch search) {
		return adminQueryService.searchAccounts(search, 0, 20).getContent().stream().map(Account::getId).toList();
	}

	private static AccountSearch opened(String tag, LocalDate from, LocalDate to) {
		return new AccountSearch(tag, null, new DateRange(from, to), null, null);
	}

	/** openedAt 으로 개설 시각을 바꾼 계좌와 그 원래 시각. 테스트가 끝나면 되돌린다. */
	private final Map<Long, Timestamp> backdated = new HashMap<>();

	/**
	 * 개설 시각을 정해 둔 계좌. 개설은 지금 시각으로만 되므로 만든 뒤 DB 에서 바꾼다. 앱 계정은 개설일을 바꿀 수 없어 테이블 소유자로 바꾼다.
	 * 다른 테스트(일 마감)는 같은 DB 의 모든 계좌가 오늘 생겼다고 보므로, 바꾼 시각은 테스트 뒤 restoreOpenedAt 에서 되돌린다.
	 */
	private Account openedAt(String ownerName, LocalDateTime createdAt) {
		Account account = accountService.open(ownerName);
		backdated.put(account.getId(), Timestamp.valueOf(account.getCreatedAt()));
		owner.jdbc().update("UPDATE account SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), account.getId());
		return account;
	}

	@AfterEach
	void restoreOpenedAt() {
		backdated.forEach((id, original) ->
				owner.jdbc().update("UPDATE account SET created_at = ? WHERE id = ?", original, id));
		backdated.clear();
	}

	private String adminToken() throws Exception {
		String body = mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"test-admin\",\"password\":\"test-admin-password-1234\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	/** 요약 숫자가 DB 와 맞고, 정상 거래만 있으면 원장 점검은 balanced 다. */
	@Test
	void summaryMatchesDatabaseAndLedgerIsBalanced() {
		Account a = accountService.open(tag());
		Account b = accountService.open(tag());
		accountService.deposit(a.getId(), 10_000, newKey());
		transferService.transfer(a.getId(), b.getId(), 3_000, newKey());

		AdminSummary summary = adminQueryService.summary();

		assertThat(summary.accountCount()).isEqualTo(
				jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Long.class));
		assertThat(summary.activeAccountCount() + summary.closedAccountCount()).isEqualTo(summary.accountCount());
		assertThat(new BigDecimal(summary.totalBalance())).isEqualByComparingTo(
				jdbcTemplate.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM account", BigDecimal.class));
		assertThat(summary.transactionCount()).isEqualTo(
				jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class));
		assertThat(summary.todayTransactionCount()).isGreaterThanOrEqualTo(3);
		// "오늘" 은 앱 시각 기준이다. DB(UTC) 의 SYSDATE 로 세면 한국 시각 0~9시에 어제 거래까지 섞인다.
		assertThat(summary.todayTransactionCount()).isEqualTo(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE created_at >= ?", Long.class,
				java.sql.Timestamp.valueOf(java.time.LocalDate.now().atStartOfDay())));
		assertThat(summary.ledger().balanced()).isTrue();
		assertThat(summary.ledger().mismatchedAccounts()).isZero();
	}

	/** 잔액 합계가 long 범위를 넘어도 정확하다. 한도 시험용 계좌(long 최대값) 두 개를 만든다. */
	@Test
	void totalBalanceDoesNotOverflow() {
		accountService.deposit(accountService.open(tag()).getId(), Long.MAX_VALUE, newKey());
		accountService.deposit(accountService.open(tag()).getId(), Long.MAX_VALUE, newKey());

		BigDecimal total = new BigDecimal(adminQueryService.summary().totalBalance());

		assertThat(total).isGreaterThan(BigDecimal.valueOf(Long.MAX_VALUE));
	}

	/** 원장을 SQL 로 몰래 고치면 점검이 잡아낸다. 확인 뒤 되돌린다. */
	@Test
	void summaryDetectsTamperedBalance() {
		Account account = accountService.open(tag());
		accountService.deposit(account.getId(), 5_000, newKey());
		jdbcTemplate.update("UPDATE account SET balance = balance + 1 WHERE id = ?", account.getId());
		try {
			AdminSummary.LedgerCheck check = adminQueryService.summary().ledger();

			assertThat(check.balanced()).isFalse();
			assertThat(check.mismatchedAccounts()).isGreaterThanOrEqualTo(1);
		} finally {
			jdbcTemplate.update("UPDATE account SET balance = balance - 1 WHERE id = ?", account.getId());
		}
	}

}
