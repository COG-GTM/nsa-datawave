# Ingest data types

DataWave ships a set of ingest data types under `warehouse/`. Each one supplies a Hadoop
`InputFormat`/`RecordReader` pair that turns a raw file into `RawRecordContainer` events, an
ingest helper that extracts and normalizes the event's fields, and a column based handler that
writes the shard, shard index, and reverse index entries. The types differ only in how they read
the raw file; everything from field normalization onward is shared, so records of the same
logical schema land in the same tables with the same keys regardless of the file format they
arrived in.

| Type    | Module                     | Input format / reader                                      | Ingest helper                | Handler                              | Notes |
|---------|----------------------------|------------------------------------------------------------|------------------------------|--------------------------------------|-------|
| CSV     | `warehouse/ingest-csv`     | `CSVFileInputFormat` / `CSVRecordReader`                   | `ExtendedCSVIngestHelper`    | `ContentCSVColumnBasedHandler`       | [csv.md](csv.md) |
| JSON    | `warehouse/ingest-json`    | `JsonInputFormat` / `JsonRecordReader`                     | `JsonIngestHelper`           | `ContentJsonColumnBasedHandler`      | [json.md](json.md) |
| Parquet | `warehouse/ingest-parquet` | `ParquetInputFormat` / `ParquetRecordReader`               | `ParquetIngestHelper`        | `ContentParquetColumnBasedHandler`   | [parquet.md](parquet.md) |

Example configurations for each type live in
`warehouse/ingest-configuration/src/main/resources/config/` (`mycsv-ingest-config.xml`,
`myjson-ingest-config.xml`, `myparquet-ingest-config.xml`).
