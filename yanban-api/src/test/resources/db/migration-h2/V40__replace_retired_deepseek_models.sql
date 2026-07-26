UPDATE sys_user_settings
SET deepseek_model = 'deepseek-v4-flash'
WHERE deepseek_model IN ('deepseek-chat', 'deepseek-reasoner');

UPDATE sys_user_settings
SET deepseek_models = '["deepseek-v4-flash","deepseek-v4-pro"]'
WHERE deepseek_models IS NULL
   OR deepseek_models LIKE '%deepseek-chat%'
   OR deepseek_models LIKE '%deepseek-reasoner%';

UPDATE agent_sessions
SET model_snapshot = 'deepseek-v4-flash'
WHERE model_provider_snapshot = 'deepseek'
  AND model_snapshot IN ('deepseek-chat', 'deepseek-reasoner');

DELETE FROM user_models
WHERE provider_key = 'deepseek'
  AND is_builtin = TRUE
  AND model_name IN ('deepseek-chat', 'deepseek-reasoner');
