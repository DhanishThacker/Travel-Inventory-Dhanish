package com.example.travel.transaction.metadata;

import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** navan_fee: Navan-owned charge. fee_kind must be a known kind. */
@Component
public class NavanFeeMetadataValidator implements MetadataValidator {

    static final Set<String> FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

    @Override
    public TransactionType type() {
        return TransactionType.NAVAN_FEE;
    }

    @Override
    public void validate(JsonNode metadata) {
        String root = "metadata";
        String kind = MetadataChecks.text(metadata, "fee_kind", root);
        if (!FEE_KINDS.contains(kind)) {
            throw MetadataChecks.fail(root + ".fee_kind '" + kind + "' is not supported; expected one of: trip_fee, agent_call_fee");
        }
        MetadataChecks.optionalText(metadata, "description", root);
        MetadataChecks.optionalText(metadata, "related_external_id", root);
    }
}
