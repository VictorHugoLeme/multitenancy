CREATE TABLE IF NOT EXISTS tenant (
    id     bigint       NOT NULL AUTO_INCREMENT,
    code   varchar(3)   NOT NULL,
    name   varchar(255) NOT NULL,
    active bit(1)       NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_code (code),
    UNIQUE KEY uq_tenant_name (name)
);