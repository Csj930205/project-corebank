package com.corebank;

import org.springframework.boot.SpringApplication;

/**
 * compose.yaml 없이 테스트용 Oracle 컨테이너를 자동으로 띄워 앱을 실행하는 개발용 시작점. Docker 가 필요하다.
 */
public class TestProjectCorebankApplication {

	public static void main(String[] args) {
		SpringApplication.from(ProjectCorebankApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
