CREATE TABLE shared_model_providers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 chat_url VARCHAR(512) NOT NULL,
 models_url VARCHAR(512) NULL,
 api_key_encrypted TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE shared_models (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 provider_id BIGINT NOT NULL,
 model_name VARCHAR(128) NOT NULL,
 approved BOOLEAN NOT NULL DEFAULT FALSE,
 supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
 available BOOLEAN NOT NULL DEFAULT TRUE,
 manual BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT uk_shared_model UNIQUE (provider_id, model_name),
 CONSTRAINT fk_shared_model_provider FOREIGN KEY (provider_id) REFERENCES shared_model_providers(id)
);
