-- Tabele dodane po pierwszym wdrożeniu (reszta schematu powstała ręcznie w MySQL `mealquo`).
-- 15.6: zgoda rodzica dla kont 13–15 lat (prośba e-mailem, potwierdzenie linkiem)
CREATE TABLE IF NOT EXISTS parent_consents (
  user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  parent_email VARCHAR(255) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmed_at TIMESTAMP NULL DEFAULT NULL,
  mail_sent TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY token_hash (token_hash),
  CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
