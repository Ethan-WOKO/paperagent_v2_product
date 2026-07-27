UPDATE sys_user_settings
SET default_provider = 'deepseek'
WHERE default_provider IN ('openrouter-hy3-free', 'openrouter-hy3');

DELETE FROM user_models
WHERE provider_key IN ('openrouter-hy3-free', 'openrouter-hy3')
  AND is_builtin = TRUE;
