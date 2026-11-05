# CSV ingest type

Module: `warehouse/ingest-csv`. Example config: `config/mycsv-ingest-config.xml`.

* `datawave.ingest.csv.mr.input.CSVFileInputFormat` reads one delimited file per mapper (not splittable).
* `datawave.ingest.csv.mr.input.CSVRecordReader` tokenizes each line using `<type>.data.separator`, maps the leading
  tokens onto `<type>.data.header`, and treats trailing `NAME=VALUE` tokens as extra fields when
  `<type>.data.process.extra.fields` is true. Multi-valued fields are split on `<type>.data.multivalued.separator`.
* `datawave.ingest.csv.config.helper.ExtendedCSVIngestHelper` normalizes the fields using the standard
  `<type>.data.default.type.class` / `<type>.<FIELD>.data.field.type.class` settings.
* `datawave.ingest.csv.mr.handler.ContentCSVColumnBasedHandler` writes the shard, index, and reverse index entries.

Required settings: `<type>.data.header` (unless `<type>.data.header.enabled=false`) and `<type>.data.separator`.
