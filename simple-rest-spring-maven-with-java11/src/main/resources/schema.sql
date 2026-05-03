CREATE TABLE IF NOT EXISTS items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(500),
    price       BIGINT        NOT NULL DEFAULT 0
);
