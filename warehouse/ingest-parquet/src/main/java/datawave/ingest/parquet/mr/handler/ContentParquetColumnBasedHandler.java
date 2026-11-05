package datawave.ingest.parquet.mr.handler;

import datawave.ingest.data.config.ingest.AbstractContentIngestHelper;
import datawave.ingest.mapreduce.handler.tokenize.ContentIndexingColumnBasedHandler;
import datawave.ingest.parquet.config.helper.ParquetIngestHelper;

/**
 * Column based handler for Parquet events. Writes the same shard, index, and reverse index entries as the CSV and JSON handlers.
 */
public class ContentParquetColumnBasedHandler<KEYIN> extends ContentIndexingColumnBasedHandler<KEYIN> {

    @Override
    public AbstractContentIngestHelper getContentIndexingDataTypeHelper() {
        return (ParquetIngestHelper) helper;
    }
}
