package com.corebank.ops;

import com.corebank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상태 확인 주소. 배포 스크립트가 토큰 없이 부르고, 세부 내용과 다른 actuator 주소는 열지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	/** 토큰 없이 UP 이 나온다(DB 연결 포함). DB 종류 같은 세부 내용은 보이지 않는다. */
	@Test
	void healthIsPublicAndHidesDetails() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	/** 환경 변수·설정을 보여 주는 주소는 열지 않는다(비밀번호가 들어 있다). */
	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
	}

}
