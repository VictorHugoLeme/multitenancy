CREATE TABLE IF NOT EXISTS product (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(500)    DEFAULT NULL,
    price       DECIMAL(10, 2)  NOT NULL,
    active      BIT(1)          NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);