# Parquet ingest type

Module: `warehouse/ingest-parquet` (`gov.nsa.datawave:datawave-ingest-parquet`). Example config:
`warehouse/ingest-configuration/src/main/resources/config/myparquet-ingest-config.xml`.

The Parquet type reads [Apache Parquet](https://parquet.apache.org/) files with `parquet-hadoop`
and feeds each row through the same normalization and handler path as the CSV and JSON types. A
Parquet file and a CSV file carrying the same logical records therefore produce identical shard,
shard index, and reverse index entries.

## Classes

| Role          | Class |
|---------------|-------|
| Input format  | `datawave.ingest.parquet.mr.input.ParquetInputFormat` – one file per mapper, not splittable |
| Record reader | `datawave.ingest.parquet.mr.input.ParquetRecordReader` – one event per row |
| Type helper   | `datawave.ingest.parquet.config.helper.ParquetDataTypeHelper` |
| Ingest helper | `datawave.ingest.parquet.config.helper.ParquetIngestHelper` |
| Handler       | `datawave.ingest.parquet.mr.handler.ContentParquetColumnBasedHandler` |
| Flattening    | `datawave.ingest.parquet.util.ParquetGroupFlattener` |

```xml
<property><name>file.input.format</name><value>datawave.ingest.parquet.mr.input.ParquetInputFormat</value></property>
<property><name>data.name</name><value>myparquet</value></property>
<property><name>myparquet.ingest.helper.class</name><value>datawave.ingest.parquet.config.helper.ParquetIngestHelper</value></property>
<property><name>myparquet.reader.class</name><value>datawave.ingest.parquet.mr.input.ParquetRecordReader</value></property>
<property><name>myparquet.handler.classes</name><value>datawave.ingest.parquet.mr.handler.ContentParquetColumnBasedHandler</value></property>
```

## How rows become fields

`ParquetGroupFlattener` turns each row into field/value pairs:

* Every column becomes a field. Names are upper cased (`<type>.data.parquet.field.name.uppercase`,
  default `true`).
* Nested groups are flattened by joining the path with `<type>.data.parquet.field.name.delimiter`
  (default `_`): `address.city` becomes `ADDRESS_CITY`.
* Repeated columns and `LIST` columns become multi-valued fields; the `list`/`element` wrapper
  groups do not appear in the name. `MAP` columns yield `<NAME>_KEY` and `<NAME>_VALUE`.
* Values are rendered as the text a CSV of the same schema would hold:

  | Parquet type                          | Field value |
  |---------------------------------------|-------------|
  | `BOOLEAN`                             | `true` / `false` |
  | `INT32`, `INT64`                      | decimal integer |
  | `FLOAT`, `DOUBLE`                     | Java `toString` |
  | `DECIMAL` (int or binary backed)      | plain decimal, e.g. `1234.50` |
  | `DATE`                                | `yyyy-MM-dd` |
  | `TIME`                                | ISO local time |
  | `TIMESTAMP` (millis/micros/nanos), `INT96` | `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC) |
  | `STRING`, `ENUM`, `JSON`, plain `BINARY` | UTF-8 text |
  | `UUID`                                | canonical UUID text |

The flattened fields are stored on the event as canonical JSON (`{"FIELD":["v1","v2"],...}`), which
`ParquetIngestHelper` reads back so that the ingest helper never needs the Parquet file itself.

## Configuration

Parquet files are self-describing, so unlike CSV no header is required:

* Omit `<type>.data.header` to accept every column in the file schema.
* Set `<type>.data.header` to restrict fields: with `<type>.data.process.extra.fields=false` only
  header fields are kept; with `true` the header merely names the expected fields.
* `<type>.data.separator` defaults to `,` and is only used for list-valued configuration values.
* `<type>.data.category.marking.visibility.field` names a column holding the event's column
  visibility; otherwise `<type>.data.category.marking.default` applies.
* `<type>.data.field.allowlist` / `<type>.data.field.disallowlist` keep or drop columns by name (a disallowlist works on its own for Parquet).
* All standard datatype settings (`.data.category.date`, `.data.category.date.formats`,
  `.data.category.uid.fields`, `.data.category.index`, `.data.category.index.reverse`,
  `.data.default.type.class`, `<FIELD>.data.field.type.class`, tokenization, policy enforcement, ...)
  are honored exactly as for CSV and JSON.

## Tests

* Unit tests in `warehouse/ingest-parquet/src/test` mirror the CSV/JSON tests (reader, type
  helper, ingest helper, flattener, column based handler). `ParquetTestFixtures` regenerates the
  `input/my.parquet` fixture; `ParquetTestFileWriter.csvToParquet` converts any CSV fixture.
* `datawave.query.parquet.ParquetCsvEquivalenceQueryTest` in `warehouse/query-core` ingests
  `input/paris-cities.csv` through the CSV type and its Parquet twin through the Parquet type, then
  asserts the query API returns identical documents for the same queries.

## Dependency

`org.apache.parquet:parquet-hadoop:${version.parquet}` (1.13.1), declared in the root `pom.xml`. The
jar is added to the ingest classpath by `ingest-scripts` (`findJars.sh` / `ingest-libs.sh`) and to the
assembled distribution.
