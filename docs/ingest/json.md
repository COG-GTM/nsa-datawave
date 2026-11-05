# JSON ingest type

Module: `warehouse/ingest-json`. Example config: `config/myjson-ingest-config.xml`.

* `datawave.ingest.json.mr.input.JsonInputFormat` reads one file per mapper (not splittable). A file may contain a single
  object, an array of objects, or concatenated objects; each object becomes one event.
* `datawave.ingest.json.mr.input.JsonRecordReader` flattens nested objects according to `<type>.data.json.flattener.mode`
  (`SIMPLE`, `NORMAL`, `GROUPED`, `GROUPED_AND_NORMAL`) and stores the original JSON as the event's raw data.
* `datawave.ingest.json.config.helper.JsonIngestHelper` re-flattens the raw JSON and normalizes the fields.
* `datawave.ingest.json.mr.handler.ContentJsonColumnBasedHandler` writes the shard, index, and reverse index entries.

Optional settings: `<type>.data.category.marking.visibility.field` names a field carrying the event's column visibility.
