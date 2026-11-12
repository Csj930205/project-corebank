package com.corebank.admin;

import com.corebank.TestcontainersConfiguration;
import com.corebank.auth.JwtTokenService;
import com.corebank.customer.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 등록. 정정 승인에 두 번째 관리자가 필요해서 생긴 기능이다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private CustomerService customerService;

	private String admin() {
		return "Bearer " + tokenService.issueAdminToken("test-admin").value();
	}

	private static String newName() {
		return "adm" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private String body(String username, String password) {
		return "{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}";
	}

	/** 등록한 관리자는 로그인하면 관리자 토큰을 받는다. 응답에 비밀번호 해시는 없다. */
	@Test
	void registeredAdminCanLogIn() throws Exception {
		String username = newName();
		mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON).content(body(username, "second-admin-pw")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(username, "second-admin-pw")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
		mockMvc.perform(get("/admin/admins").header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.content[?(@.username == '" + username + "')]").exists());
	}

	/** 관리자·고객 아이디와 겹치면 409, 짧은 비밀번호·대문자 아이디는 400 */
	@Test
	void rejectsTakenNamesAndWeakInput() throws Exception {
		String username = newName();
		String customer = newName();
		customerService.signUp(customer, "customer-password", "고객");
		mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, admin())
				.contentType(MediaType.APPLICATION_JSON).content(body(username, "second-admin-pw")));

		for (String taken : new String[] {username, customer}) {
			mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, admin())
							.contentType(MediaType.APPLICATION_JSON).content(body(taken, "second-admin-pw")))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
		}
		for (String bad : new String[] {body(newName(), "short"), body("Upper" + newName(), "second-admin-pw")}) {
			mockMvc.perform(post("/admin/admins").header(HttpHeaders.AUTHORIZATION, admin())
							.contentType(MediaType.APPLICATION_JSON).content(bad))
					.andExpect(status().isBadRequest());
		}
	}

}
