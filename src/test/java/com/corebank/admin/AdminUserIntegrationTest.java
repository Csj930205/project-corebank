package com.corebank.admin;

import com.corebank.TestcontainersConfiguration;
import com.corebank.auth.AuthSessionService;
import com.corebank.auth.JwtTokenService;
import com.corebank.customer.CustomerService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 역할 분리와 등록 승인.
 *
 * <p>운영자(test-admin)와 책임자(test-approver)는 테스트 설정으로 서버 시작 때 만들어진다(AdminBootstrap).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserIntegrationTest {

	private static final String OPERATOR = "test-admin";
	private static final String APPROVER = "test-approver";
	private static final String PASSWORD = "new-admin-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuthSessionService tokenService;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private CustomerService customerService;

	@Autowired
	private AdminUserService adminUserService;

	private String token(String username, AdminRole role) {
		return "Bearer " + tokenService.issueAdminToken(username, role).value();
	}

	private String operator() {
		return token(OPERATOR, AdminRole.OPERATOR);
	}

	private String approver() {
		return token(APPROVER, AdminRole.APPROVER);
	}

	private static String newName() {
		return "adm" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private static String body(String username, String password, String role) {
		return "{\"username\": \"" + username + "\", \"password\": \"" + password + "\""
				+ (role == null ? "" : ", \"role\": \"" + role + "\"") + "}";
	}

	private MockHttpServletRequestBuilder login(String username, String password) {
		return post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}");
	}

	/** 운영자가 요청한 id */
	private long request(String username, AdminRole role) throws Exception {
		String response = mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, operator())
						.contentType(MediaType.APPLICATION_JSON).content(body(username, PASSWORD, role.name())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	/** 서버 설정으로 만든 두 계정은 요청·승인 없이 ACTIVE 이고, 로그인하면 역할을 알려 준다. */
	@Test
	void bootstrapCreatesOperatorAndApprover() throws Exception {
		mockMvc.perform(get("/admin/admins?status=ACTIVE").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(jsonPath("$.content[?(@.username == '" + OPERATOR + "')].role").value("OPERATOR"))
				.andExpect(jsonPath("$.content[?(@.username == '" + APPROVER + "')].role").value("APPROVER"))
				.andExpect(jsonPath("$.content[?(@.username == '" + APPROVER + "')].requestedBy").value((Object) null));
		mockMvc.perform(login(APPROVER, "test-approver-password-1234"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.adminRole").value("APPROVER"));
	}

	/**
	 * 운영자가 요청하면 PENDING 이고 로그인할 수 없다. 책임자가 승인하면 로그인되고 토큰에 역할이 실린다.
	 * 승인 전 로그인 실패는 비밀번호가 틀렸을 때와 똑같은 401 이다(승인 대기 중인 아이디가 있다는 것도 알려 주지 않는다).
	 */
	@Test
	void registrationNeedsApprovalBeforeLogin() throws Exception {
		String username = newName();
		String response = mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, operator())
						.contentType(MediaType.APPLICATION_JSON).content(body(username, PASSWORD, "TELLER")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.role").value("TELLER"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.requestedBy").value(OPERATOR))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(response, "$.id")).longValue();

		mockMvc.perform(login(username, PASSWORD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mockMvc.perform(get("/admin/admins?status=PENDING").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(jsonPath("$.content[?(@.id == " + id + ")].username").value(username));

		mockMvc.perform(post("/admin/admins/" + id + "/approval").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.decidedBy").value(APPROVER))
				.andExpect(jsonPath("$.decidedAt").isNotEmpty());
		mockMvc.perform(post("/admin/admins/" + id + "/rejection").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ADMIN_ALREADY_DECIDED"));

		String token = JsonPath.read(mockMvc.perform(login(username, PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.adminRole").value("TELLER"))
				.andReturn().getResponse().getContentAsString(), "$.accessToken");
		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.adminRole").value("TELLER"));
		// 승인된 창구는 계좌를 연다.
		mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\": \"홍길동\"}"))
				.andExpect(status().isCreated());
	}

	/** 반려된 계정은 로그인할 수 없고, 아이디는 계속 쓰는 중으로 남는다(누가 반려했는지 기록이 남아야 한다). */
	@Test
	void rejectedAdminCannotLogIn() throws Exception {
		String username = newName();
		long id = request(username, AdminRole.APPROVER);
		mockMvc.perform(post("/admin/admins/" + id + "/rejection").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));

		mockMvc.perform(login(username, PASSWORD)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, operator())
						.contentType(MediaType.APPLICATION_JSON).content(body(username, PASSWORD, "TELLER")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
		mockMvc.perform(post("/admin/admins/999999999/approval").header(HttpHeaders.AUTHORIZATION, approver()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ADMIN_NOT_FOUND"));
	}

	/**
	 * 요청자가 스스로 결정할 수 없다. HTTP 로는 역할(요청은 운영자, 결정은 책임자)에서 먼저 막히므로
	 * 서비스를 직접 불러 엔티티의 규칙을 본다. 역할 규칙을 잘못 고쳐도 여기서 한 번 더 막힌다.
	 */
	@Test
	void requesterCannotDecideOwnRegistration() throws Exception {
		long id = request(newName(), AdminRole.TELLER);
		assertThatThrownBy(() -> adminUserService.approve(id, OPERATOR)).isInstanceOf(AdminSelfApprovalException.class);
		assertThatThrownBy(() -> adminUserService.reject(id, OPERATOR)).isInstanceOf(AdminSelfApprovalException.class);
		assertThat(adminUserService.approve(id, APPROVER).getStatus()).isEqualTo(AdminUserStatus.ACTIVE);
	}

	/** 관리자·고객 아이디와 겹치면 409, 짧은 비밀번호·대문자 아이디·역할 없음·없는 역할은 400 */
	@Test
	void rejectsTakenNamesAndWeakInput() throws Exception {
		String username = newName();
		String customer = newName();
		customerService.signUp(customer, "customer-password", "고객");
		request(username, AdminRole.TELLER);

		for (String taken : new String[] {username, customer}) {
			mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, operator())
							.contentType(MediaType.APPLICATION_JSON).content(body(taken, PASSWORD, "TELLER")))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
		}
		for (String bad : new String[] {body(newName(), "short", "TELLER"), body("Upper" + newName(), PASSWORD, "TELLER"),
				body(newName(), PASSWORD, null), body(newName(), PASSWORD, "ROOT")}) {
			mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, operator())
							.contentType(MediaType.APPLICATION_JSON).content(bad))
					.andExpect(status().isBadRequest());
		}
	}

	/**
	 * 역할 표. 각 변경 API 를 맡은 역할이 아닌 관리자가 부르면 403 FORBIDDEN 이다.
	 * 권한 확인은 컨트롤러보다 앞(보안 필터)이라 요청 내용이 엉터리여도 403 이 먼저다. 그래서 본문 없이 보낸다.
	 */
	@Test
	void eachChangeNeedsItsRole() throws Exception {
		Map<String, AdminRole> owner = Map.ofEntries(
				Map.entry("/accounts", AdminRole.TELLER),
				Map.entry("/accounts/1/deposits", AdminRole.TELLER),
				Map.entry("/accounts/1/withdrawals", AdminRole.TELLER),
				Map.entry("/accounts/1/closure", AdminRole.TELLER),
				Map.entry("/transfers", AdminRole.TELLER),
				Map.entry("/admin/corrections", AdminRole.TELLER),
				Map.entry("/admin/corrections/1/approval", AdminRole.APPROVER),
				Map.entry("/admin/corrections/1/rejection", AdminRole.APPROVER),
				Map.entry("/admin/admins/1/approval", AdminRole.APPROVER),
				Map.entry("/admin/admins/1/rejection", AdminRole.APPROVER),
				Map.entry("/admin/admins", AdminRole.OPERATOR),
				Map.entry("/admin/closings", AdminRole.OPERATOR),
				Map.entry("/admin/interest/accruals", AdminRole.OPERATOR),
				Map.entry("/admin/interest/settlements", AdminRole.OPERATOR),
				Map.entry("/admin/products", AdminRole.OPERATOR),
				Map.entry("/admin/products/1/discontinuation", AdminRole.OPERATOR),
				Map.entry("/admin/login-locks/someone/unlock", AdminRole.OPERATOR));
		for (var entry : owner.entrySet()) {
			for (AdminRole role : AdminRole.values()) {
				if (role == entry.getValue()) {
					continue;
				}
				mockMvc.perform(post(entry.getKey()).header(HttpHeaders.AUTHORIZATION, token("someone", role)))
						.andExpect(status().isForbidden())
						.andExpect(jsonPath("$.code").value("FORBIDDEN"));
			}
		}
		// 목록에 없는 관리자 변경은 아무 역할에도 열리지 않는다(새 API 에 규칙을 잊으면 막힌 채로 드러난다).
		for (AdminRole role : AdminRole.values()) {
			mockMvc.perform(post("/admin/summary").header(HttpHeaders.AUTHORIZATION, token("someone", role)))
					.andExpect(status().isForbidden());
		}
	}

	/** 조회는 역할과 상관없이 모든 관리자가 한다. 책임자도 정정을 승인하려면 거래를 봐야 한다. */
	@Test
	void everyAdminCanRead() throws Exception {
		for (AdminRole role : AdminRole.values()) {
			for (String path : new String[] {"/admin/accounts", "/admin/summary", "/admin/corrections", "/admin/admins",
					"/admin/closings", "/admin/products", "/admin/audit-logs", "/products"}) {
				mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token("someone", role)))
						.andExpect(status().isOk());
			}
		}
	}

	/**
	 * 로그인 세션 기능 전에 발급된 토큰(세션 번호 sid 가 없음)은 서명이 맞아도 401 이다. 배포 직후 한 번 다시 로그인하면 된다.
	 * (역할이 없던 더 옛날 토큰도 sid 가 없으므로 같이 막힌다.)
	 */
	@Test
	void tokenWithoutSessionIsRejected() throws Exception {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(JwtTokenService.ISSUER).subject(OPERATOR)
				.issuedAt(now).expiresAt(now.plusSeconds(600)).claim("scope", "ADMIN").claim("role", "OPERATOR").build();
		String legacy = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, legacy))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/admin/closings").header(HttpHeaders.AUTHORIZATION, legacy))
				.andExpect(status().isUnauthorized());
	}

}
