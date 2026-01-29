package com.proxy.interceptor;

import com.proxy.interceptor.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@RequiredArgsConstructor
@Slf4j
public class InterceptorApplication {

	public static void main(String[] args) {
		SpringApplication.run(InterceptorApplication.class, args);
	}

	@Bean
	CommandLineRunner initAdmin(AuthService authService) {
		return args -> {
			// Create default admin user if not exists
			authService.createAdminIfNotExists("admin", "14495abc");
			log.info("	=========================================");
			log.info("		Interceptor Proxy v2.0 Started");
			log.info("	=========================================");
			log.info("		Dashboard: http://localhost:3000");
			log.info("		Proxy Port: 5432");
			log.info("		DB Engine: localhost:5433");
			log.info("	=========================================");
		};
	}

}
