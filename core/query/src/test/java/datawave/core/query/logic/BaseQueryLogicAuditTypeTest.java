package datawave.core.query.logic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import datawave.core.query.logic.composite.CompositeQueryLogic;
import datawave.core.query.logic.lookup.uid.LookupUIDQueryLogic;
import datawave.webservice.common.audit.Auditor.AuditType;

/**
 * Spring 6 removed {@code @Required}; {@link BaseQueryLogic#afterPropertiesSet()} keeps a missing auditType a startup failure.
 */
public class BaseQueryLogicAuditTypeTest {

    @Test
    public void missingAuditTypeFailsAtStartup() {
        BaseQueryLogic<?> logic = new CompositeQueryLogic();
        assertThrows(IllegalStateException.class, logic::afterPropertiesSet);
    }

    @Test
    public void configuredAuditTypePasses() {
        BaseQueryLogic<?> logic = new CompositeQueryLogic();
        logic.setAuditType(AuditType.NONE);
        assertDoesNotThrow(logic::afterPropertiesSet);
    }

    @Test
    public void delegatingLogicUsesTheDelegatesAuditType() {
        CompositeQueryLogic delegate = new CompositeQueryLogic();
        LookupUIDQueryLogic<Object> lookup = new LookupUIDQueryLogic<>(delegate);
        assertThrows(IllegalStateException.class, lookup::afterPropertiesSet);
        // the lookup wrapper's setter configures the delegate, not its own field, as the Spring XML definitions rely on
        lookup.setAuditType(AuditType.NONE);
        assertDoesNotThrow(lookup::afterPropertiesSet);
    }
}
