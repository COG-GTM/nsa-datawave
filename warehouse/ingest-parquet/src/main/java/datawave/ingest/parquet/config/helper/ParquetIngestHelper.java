package datawave.ingest.parquet.config.helper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import datawave.ingest.data.RawRecordContainer;
import datawave.ingest.data.config.NormalizedContentInterface;
import datawave.ingest.data.config.NormalizedFieldAndValue;
import datawave.ingest.data.config.ingest.ContentBaseIngestHelper;
import datawave.ingest.data.normalizer.SimpleGroupFieldNameParser;
import datawave.ingest.parquet.util.ParquetGroupFlattener;

/**
 * Ingest helper for Parquet events. The record reader has already flattened each row into field/value pairs and stored them as canonical JSON in the raw
 * data, so this helper only needs to rebuild that map, apply the field allow/disallow lists, and run the standard normalization so the resulting shard and
 * index entries are identical to those produced for a CSV record carrying the same fields.
 */
public class ParquetIngestHelper extends ContentBaseIngestHelper {

    protected ParquetDataTypeHelper helper = null;
    protected SimpleGroupFieldNameParser groupNormalizer = new SimpleGroupFieldNameParser();

    @Override
    public void setup(Configuration config) {
        super.setup(config);
        helper = new ParquetDataTypeHelper();
        helper.setup(config);
        this.setEmbeddedHelper(helper);
    }

    @Override
    public Multimap<String,NormalizedContentInterface> getEventFields(RawRecordContainer event) {
        if (null == helper) {
            throw new IllegalStateException("ParquetDataTypeHelper was not initialized. Method 'setup' must be invoked first");
        }

        Multimap<String,String> fields = ParquetGroupFlattener.fromJson(new String(event.getRawData(), StandardCharsets.UTF_8));

        Multimap<String,NormalizedContentInterface> results = HashMultimap.create();
        for (Map.Entry<String,String> e : fields.entries()) {
            String fieldName = e.getKey();
            String fieldValue = e.getValue();
            if (fieldValue == null || !keepField(fieldName)) {
                continue;
            }
            fieldValue = helper.clean(fieldName, fieldValue);
            if (fieldValue == null) {
                continue;
            }
            // a scalar column configured as multi-valued is split exactly as CSVIngestHelper.processPreSplitField splits it (SOURCE:DESTINATION aliases,
            // per-value cleaning, escaped separators); repeated columns arrive already split
            if (fields.get(fieldName).size() == 1 && helper.isMultiValuedField(fieldName)) {
                String singleFieldName = helper.usingMultiValuedFieldsDisallowlist() ? fieldName : helper.getMultiValuedFields().get(fieldName);
                for (String value : fieldValue.split(helper.getEscapeSafeMultiValueSeparatorPattern())) {
                    value = helper.clean(singleFieldName, value);
                    if (value != null && !value.isEmpty()) {
                        results.put(singleFieldName, new NormalizedFieldAndValue(singleFieldName, helper.cleanEscapedMultivalueSeparators(value)));
                    }
                }
            } else {
                results.put(fieldName, new NormalizedFieldAndValue(fieldName, helper.cleanEscapedMultivalueSeparators(fieldValue)));
            }
        }

        return normalizeMap(groupNormalizer.extractFieldNameComponents(results));
    }

    /**
     * Same allow/disallow semantics as the CSV ingest helper: a field on the disallow list is dropped, and when an allow list exists only its members are kept.
     *
     * @param fieldName
     *            the field name
     * @return true if the field should be kept
     */
    protected boolean keepField(String fieldName) {
        final Set<String> disallowlist = helper.getFieldDisallowlist();
        final Set<String> allowlist = helper.getFieldAllowlist();

        if (disallowlist != null && disallowlist.contains(fieldName)) {
            return false;
        }
        return allowlist == null || allowlist.contains(fieldName);
    }
}
