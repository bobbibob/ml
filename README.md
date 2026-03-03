# ml

Автосинхронизация `database_pack.zip` через Cloudflare R2 (S3 API):
- Обновить: скачивает ZIP, распаковывает, читает SQLite (svodka)
- Сохранить: конфликт-чек по manifest.version, инкремент версии, сбор ZIP, загрузка в R2

Нужные env/secrets:
R2_ENDPOINT, R2_BUCKET, R2_ACCESS_KEY, R2_SECRET_KEY, R2_OBJECT_KEY, R2_REGION, UPDATED_BY
