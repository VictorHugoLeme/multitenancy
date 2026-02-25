# Multitenancy — Spring Boot Reference

A production-ready reference implementation of **database-per-tenant multitenancy** with Spring Boot 4, JPA, and Flyway. Each tenant gets its own isolated MySQL database, provisioned and migrated automatically at runtime.

---

## How it works

```
Request
  │
  ▼
TenantInterceptorFilter          reads X-Tenant-Code header
  │  validates tenant is active
  │
  ▼
ScopedValue<String>              binds tenant code for the request scope
  │  (thread-safe, virtual-thread-friendly)
  │
  ▼
AbstractRoutingDataSource        routes to the correct tenant datasource
  │
  ▼
Tenant DB  (db_<code>)           isolated schema per tenant
```

On startup, `MultiTenantManager` reads all active tenants from the management database (`db_tenants`), creates a `HikariCP` connection pool per tenant, and runs pending Flyway migrations against each tenant database. New tenants registered at runtime are provisioned immediately without a restart.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4 (Web MVC, Data JPA) |
| Database | MySQL 8.4 |
| Migrations | Flyway 11 |
| Connection pool | HikariCP |
| Tenant context | `ScopedValue` (JEP 506) |
| Build | Maven 3.9+ |
| Tests | JUnit 5 + Testcontainers |

---

## Project structure

```
src/main/
├── java/.../multitenancy/
│   ├── config/
│   │   └── MultiTenantManager.java       # Datasource routing, Flyway orchestration
│   ├── controllers/
│   │   ├── TenantController.java         # Tenant lifecycle endpoints
│   │   ├── GeneralContextController.java # Cross-tenant endpoints (no tenant header required)
│   │   └── ProductController.java        # Example tenant-scoped resource
│   ├── dtos/
│   ├── entities/
│   ├── interceptors/
│   │   └── TenantInterceptorFilter.java  # Resolves tenant from request header
│   ├── mappers/
│   ├── repositories/
│   └── services/
│       └── TenantService.java            # In-memory tenant cache, lifecycle ops
└── resources/
    ├── application.yaml
    └── db/migration/
        ├── tenantManagement/mysql/       # Management DB migrations (tenant table)
        ├── app/mysql/                    # Per-tenant app migrations (e.g. product table)
        └── commons/mysql/               # Shared per-tenant migrations (optional)

docker/
└── mysql/
    └── 01_grant_privileges.sql          # Grants db_* wildcard access to app user
```

---

## Getting started

### Prerequisites

- Java 25 (JDK)
- Maven 3.9+
- Docker and Docker Compose

### 1. Start the database

```bash
docker compose up -d
```

This starts MySQL 8.4 on port `3315` and runs `01_grant_privileges.sql`, which grants the `multitenancy` user permission to create and manage any `db_*` database — required for automatic tenant provisioning.

### 2. Run the application

```bash
mvn spring-boot:run
```

On first startup, Flyway will:
1. Create and migrate `db_tenants` (management database — stores the `tenant` table)
2. Load all active tenants and provision a connection pool + run migrations for each

### 3. Run tests

```bash
mvn test
```

---

## Configuration

All settings are in `src/main/resources/application.yaml`.

| Property | Default | Description |
|---|---|---|
| `multitenancy.db.host` | `localhost` | MySQL host |
| `multitenancy.db.port` | `3306` | MySQL port |
| `multitenancy.db.settings` | — | JDBC URL parameters appended after `?` |
| `multitenancy.app-locations` | — | Classpath path to per-tenant Flyway migrations |
| `multitenancy.app-migration-table` | `flyway_schema_history` | History table for app migrations |
| `multitenancy.parallel-migration` | `true` | Migrate tenant DBs concurrently on startup |
| `multitenancy.tenant-refresh-rate-ms` | `3600000` | How often the tenant cache is refreshed (ms) |

Tenant databases are named `db_<tenant_code>` (e.g. `db_bra`). The management database is always `db_tenants`.

---

## API reference

### Tenant management

All tenant endpoints operate on the management database and do not require the `X-Tenant-Code` header.

| Method | Path | Description                                                                                          |
|---|---|------------------------------------------------------------------------------------------------------|
| `POST` | `/v1/tenants` | Create a new tenant and provision its database                                                       |
| `GET` | `/v1/tenants` | List all tenants                                                                                     |
| `PATCH` | `/v1/tenants/enable/{code}` | Enable a tenant and add it to the active cache                                                       |
| `PATCH` | `/v1/tenants/disable/{code}` | Disable a tenant and remove it from the active cache                                                 |
| `POST` | `/v1/tenants/revalidate-datasources` | Force a refresh of the active tenant cache (Used if enabling/disabling tenants directly in database) |

**Create tenant request body:**
```json
{
  "name": "Brazil",
  "code": "BRA"
}
```

### General context

Endpoints that operate across all tenants without requiring a `X-Tenant-Code` header. Requests are excluded from the tenant filter and run against the management datasource by default, but individual operations may internally iterate over all tenant datasources.

| Method | Path | Description |
|---|---|---|
| `GET` | `/v1/general/products/count` | Count total products across all active tenants |

### Tenant-scoped resources

All other endpoints require the `X-Tenant-Code` header. Requests without it, or with an inactive tenant code, are rejected at the filter level.

```
X-Tenant-Code: BRA
```

| Method | Path | Description |
|---|---|---|
| `GET` | `/v1/products` | List active products for the current tenant |
| `POST` | `/v1/products` | Create a product in the current tenant's database |

**Create product request body:**
```json
{
  "name": "Keyboard",
  "description": "Mechanical keyboard",
  "price": 39.90
}
```

---

## Postman collection

A ready-to-use Postman collection is included: **`Multitenancy App.postman_collection.json`**.

Import it into Postman and set the `port` collection variable (default: `8080`).

The collection is organised into three folders:

| Folder | Contents |
|---|---|
| **Tenants** | Create tenants BRA and CAN, revalidate datasources |
| **Products BRA** | Create and list products scoped to tenant BRA |
| **Products CAN** | Create and list products scoped to tenant CAN |

The `Products BRA` and `Products CAN` folders use Postman's **API Key** authentication set at the folder level. The `X-Tenant-Code` header is automatically injected into every request in that folder — no per-request configuration needed. This also demonstrates tenant isolation: a product created under BRA will not appear when querying under CAN.

**Suggested walkthrough:**
1. `POST /v1/tenants` → create BRA
2. `POST /v1/tenants` → create CAN
3. `POST /v1/products` (BRA folder) → create a product in BRA's database
4. `POST /v1/products` (CAN folder) → create a different product in CAN's database
5. `GET /v1/products` in both folders → confirm each tenant only sees its own data

---

## Extending this reference

### Resolving tenant from a JWT

Replace `TenantInterceptorFilter` with a filter that extracts the tenant code from the token claims instead of a header:

```java
String tenantCode = jwtService.extractClaim(token, claims -> claims.get("tenantCode", String.class));
```

Everything downstream (`ScopedValue` binding, datasource routing) stays unchanged.

### Resolving tenant from a subdomain

Extract the tenant code from the request's `Host` header:

```java
String host = request.getServerName();           // e.g. bra.yourdomain.com
String tenantCode = host.split("\\.")[0].toUpperCase();
```

### Iterating over all tenants (background jobs and cross-tenant queries)

`TenantService.iterateOverTenants` executes a lambda in each active tenant's datasource context. The `ScopedValue` binding is set per iteration, so every repository call inside the lambda automatically hits the correct tenant database.

This project includes a concrete example: `ProductService.countAllTenantProducts()` accumulates the product count across all tenants and is exposed at `GET /v1/general/products/count` through `GeneralContextController` — a dedicated controller for cross-tenant operations that sits outside the tenant filter.

```java
public long countAllTenantProducts() {
    AtomicLong total = new AtomicLong();
    tenantService.iterateOverTenants(_ -> total.addAndGet(productRepository.count()));
    return total.get();
}
```

The same pattern works for scheduled jobs:

```java
@Scheduled(cron = "0 0 3 * * *")
public void nightly() {
    tenantService.iterateOverTenants(tenant -> {
        // runs inside the correct tenant's datasource scope
        reportService.generate(tenant);
    });
}
```

### Adding commons migrations

Any SQL placed under `src/main/resources/db/migration/commons/mysql/` is applied to every tenant database on startup (and whenever a new tenant is provisioned). Use this for shared schema objects that every tenant requires regardless of application version.

### Per-tenant application migrations

SQL placed under `src/main/resources/db/migration/app/mysql/` (configured via `multitenancy.app-locations`) is also applied per-tenant. The difference from commons is that this location is optional and configurable, intended for your application's domain tables (e.g. `product`).

### Programmatic tenant provisioning

You can provision tenants outside the REST API by injecting `TenantService` and calling `saveTenant(dto)`. The datasource, migrations, and cache entry are all handled internally.

---

## License

MIT
