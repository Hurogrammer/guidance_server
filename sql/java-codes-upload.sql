-- Apply once to the selected guidance database before enabling source uploads.
-- Each byte sequence is hex encoded before concatenation, so separators cannot
-- collide with source names. The service stores the language in lowercase.
-- If existing rows share an identity, the unique index rejects the migration.
ALTER TABLE `JAVA_CODES`
    ADD COLUMN `SOURCE_KEY` BINARY(32) GENERATED ALWAYS AS (
        UNHEX(SHA2(CONCAT(HEX(`LANGUAGE_NAME`), ':', HEX(`PACKAGE`), ':', HEX(`CLASS_NAME`)), 256))
    ) STORED,
    ADD COLUMN `CODE_REVISION` BIGINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY `UX_JAVA_CODES_SOURCE` (`SOURCE_KEY`);
