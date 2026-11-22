-- Apply after the guidance server can read code references from FILE_NAME.
-- The combined reference can contain two 200-character keys plus the separator.
SET @code_file_name_ddl = (
    SELECT IF(CHARACTER_MAXIMUM_LENGTH < 401,
              'ALTER TABLE `CONTENT BLOCKS` MODIFY COLUMN FILE_NAME VARCHAR(401) NULL',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'CONTENT BLOCKS' AND COLUMN_NAME = 'FILE_NAME'
);
PREPARE code_file_name_migration FROM @code_file_name_ddl;
EXECUTE code_file_name_migration;
DEALLOCATE PREPARE code_file_name_migration;

UPDATE `CONTENT BLOCKS`
SET FILE_NAME = BODY, BODY = ''
WHERE TYPE = 'code' AND FILE_NAME IS NULL;
