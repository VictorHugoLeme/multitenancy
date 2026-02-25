-- Grant the application user permission to create and manage tenant databases.
-- The db_* pattern covers: db_tenants (management DB) and db_<tenant_code> (one per tenant).
GRANT CREATE, ALTER, DROP, INDEX,
      SELECT, INSERT, UPDATE, DELETE,
      REFERENCES
  ON `db_%`.*
  TO 'multitenancy'@'%';

FLUSH PRIVILEGES;
