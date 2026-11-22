-- Apply once before saving source code on a document. Existing block types are preserved.
ALTER TABLE `CONTENT BLOCKS`
    DROP CONSTRAINT CK_CONTENT_BLOCK_TYPE,
    ADD CONSTRAINT CK_CONTENT_BLOCK_TYPE_CODE CHECK (TYPE IN ('text', 'image', 'video', 'file', 'code'));
