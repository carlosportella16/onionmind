package com.onionmind;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}
