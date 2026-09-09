CREATE TABLE IF NOT EXISTS wenbiao_key (
  id BIGINT NOT NULL,
  key_fingerprint CHAR(16) NOT NULL,
  api_key VARCHAR(512) NOT NULL COMMENT '已授权问标 API key；仅服务端读取',
  state VARCHAR(16) NOT NULL,
  imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_probe_at DATETIME(3) NULL,
  last_provider_code VARCHAR(64) NULL,
  failure_count INT NOT NULL DEFAULT 0,
  cooldown_until DATETIME(3) NULL,
  retired_at DATETIME(3) NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_wenbiao_key_fingerprint (key_fingerprint),
  UNIQUE KEY uk_wenbiao_key_api_key (api_key),
  KEY idx_wenbiao_key_selection (state, last_probe_at, imported_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wenbiao_key_runtime (
  runtime_id VARCHAR(64) NOT NULL,
  active_key_id BIGINT NOT NULL,
  generation BIGINT NOT NULL DEFAULT 1,
  lease_owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (runtime_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wenbiao_key_rotation (
  request_id CHAR(36) NOT NULL,
  from_generation BIGINT NOT NULL,
  to_generation BIGINT NULL,
  from_key_id BIGINT NOT NULL,
  to_key_id BIGINT NULL,
  provider_code VARCHAR(64) NOT NULL,
  result VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  PRIMARY KEY (request_id), KEY idx_wenbiao_rotation_generation (to_generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
