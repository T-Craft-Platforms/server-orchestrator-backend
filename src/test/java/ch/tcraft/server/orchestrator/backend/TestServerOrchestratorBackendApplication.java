package ch.tcraft.server.orchestrator.backend;

import org.springframework.boot.SpringApplication;

public class TestServerOrchestratorBackendApplication {

    static void main(String[] args) {
        SpringApplication.from(ServerOrchestratorBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
