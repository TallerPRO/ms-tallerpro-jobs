package com.tallerpro.ms_tallerpro_jobs;

import com.tallerpro.ms_tallerpro_jobs.config.JobsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(JobsProperties.class)
@EnableScheduling
public class MsTallerproJobsApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsTallerproJobsApplication.class, args);
	}

}
