package com.corebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 애플리케이션 시작점. 이 패키지 아래의 컨트롤러, 서비스 등을 찾아 스프링 빈으로 등록하고 내장 서버를 띄운다.
 */
@SpringBootApplication
public class ProjectCorebankApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectCorebankApplication.class, args);
	}

}
