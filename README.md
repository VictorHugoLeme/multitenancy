Multitenancy — Sample Project

This is a sample project to simplify the creation of multitenancy applications, handling tenant lifecycle, database connections, and schema migrations.

Project status: Bootstrap (work in progress). The goal is to provide a clean, minimal reference you can adapt to your stack.

Features
- Spring Boot baseline ready for multi-tenant patterns
- Flyway migrations integration for tenant onboarding and upgrades
- MySQL driver included; Testcontainers in tests for fast local feedback
- Maven build with Java 25 target

Tech stack
- Java 25
- Spring Boot 4 (Data JPA, Web MVC, Flyway)
- Flyway + MySQL
- Testcontainers (JUnit 5)

Getting started
Prerequisites
- Java 25 (JDK)
- Maven 3.9+
- Docker (optional; useful for running MySQL locally or when adding Testcontainers-based demos)

Clone and run
1) Install dependencies and run the application:
```
mvn spring-boot:run
```

2) Run tests:
```
mvn test
```

Configuration
- Application name is defined in `src/main/resources/application.yaml`.
- Database and tenant-specific configuration will be introduced as the multitenancy modules are added.

How this sample will evolve
Planned capabilities include:
- Tenant context resolution (e.g., by subdomain, header, or token)
- Per-tenant datasource routing
- Tenant provisioning lifecycle (create, migrate, deactivate)
- Flyway-based migration strategy per tenant (schema or database per tenant)
- Simple REST endpoints to manage and verify tenant isolation

Project layout
- `src/main/java/.../MultitenancyApplication.java` — Spring Boot entrypoint
- `src/main/resources/application.yaml` — base configuration
- `src/test/java/...` — test scaffolding with Testcontainers configuration

Contributing
Pull requests and issues are welcome. Please describe your use case and proposed changes clearly. As the project stabilizes, we’ll add contribution guidelines and code style settings.

License
TBD. If you intend to use this in a project with a specific license, open an issue so we can align the licensing early.
