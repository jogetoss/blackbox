package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import org.junit.Test;

public class CheckboxSettingTest {

    @Test
    public void neverSavedUsesTheDefault() {
        assertTrue(BlackBoxPlugin.flagValue(null, true));
        assertFalse(BlackBoxPlugin.flagValue(null, false));
    }

    @Test
    public void untickedBoxIsOffEvenWhenTheDefaultIsOn() {
        assertFalse("an unticked checkbox saves \"\"", BlackBoxPlugin.flagValue("", true));
        assertFalse("a value saved by the old select box", BlackBoxPlugin.flagValue("false", true));
    }

    @Test
    public void tickedBoxIsOn() {
        assertTrue(BlackBoxPlugin.flagValue("true", false));
    }
}
