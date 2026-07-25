package com.guildworkman.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;

@SpringBootApplication
@EnableScheduling
public class GuildWorkmanApplication {

	public static void main(String[] args) {
		SpringApplication.run(GuildWorkmanApplication.class, args);
		System.out.println(LocalDateTime.now());
	}

	// NOTE: CORS lives in config/WebConfig. A second WebMvcConfigurer used to be
	// declared here mapping "/**" to allowedOrigins("*"), competing with WebConfig
	// for the same path pattern. Keep CORS configured in exactly one place.
}
