package io.janbalangue.asyncbulkhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NamespaceTest {

    @Test
    void newNamespaceClassesExist() throws Exception {
        assertNotNull(Class.forName("io.janbalangue.asyncbulkhead.Bulkhead"));
        assertNotNull(Class.forName("io.janbalangue.asyncbulkhead.BulkheadListener"));
        assertNotNull(Class.forName("io.janbalangue.asyncbulkhead.BulkheadRejectedException"));
        assertNotNull(Class.forName("io.janbalangue.asyncbulkhead.TerminalKind"));
    }

    @Test
    void oldNamespaceClassesDoNotExist() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.janbalangue.bulkhead.Bulkhead"));

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.janbalangue.bulkhead.BulkheadListener"));

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.janbalangue.bulkhead.BulkheadRejectedException"));

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.janbalangue.bulkhead.TerminalKind"));
    }
}
