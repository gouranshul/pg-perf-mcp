package io.github.gouranshul.pgperf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PgPerfMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgPerfMcpApplication.class, args);
    }
}
