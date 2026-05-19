package com.termux.app;

import com.termux.shared.termux.TermuxConstants;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the granizm.net fork's package-rename invariants.
 * Ensures TermuxConstants and all derived paths/action-names use the
 * new applicationId (net.granizm.termux_app) instead of upstream com.termux.
 */
public class GranizmPackageRenameTest {

    private static final String EXPECTED_PACKAGE_NAME = "net.granizm.termux_app";
    private static final String EXPECTED_DATA_DIR = "/data/data/" + EXPECTED_PACKAGE_NAME;
    private static final String EXPECTED_FILES_DIR = EXPECTED_DATA_DIR + "/files";
    private static final String EXPECTED_PREFIX_DIR = EXPECTED_FILES_DIR + "/usr";

    @Test
    public void termuxPackageNameIsRenamed() {
        Assert.assertEquals(EXPECTED_PACKAGE_NAME, TermuxConstants.TERMUX_PACKAGE_NAME);
    }

    @Test
    public void privateDataDirReflectsNewPackage() {
        Assert.assertEquals(EXPECTED_DATA_DIR, TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
    }

    @Test
    public void filesDirReflectsNewPackage() {
        Assert.assertEquals(EXPECTED_FILES_DIR, TermuxConstants.TERMUX_FILES_DIR_PATH);
    }

    @Test
    public void prefixDirReflectsNewPackage() {
        Assert.assertEquals(EXPECTED_PREFIX_DIR, TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    @Test
    public void binAndEtcDerivedPaths() {
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/bin",  TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/etc",  TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/lib",  TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/share", TermuxConstants.TERMUX_SHARE_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/tmp",  TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_PREFIX_DIR + "/var",  TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH);
        Assert.assertEquals(EXPECTED_FILES_DIR + "/home",  TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    @Test
    public void noUpstreamComTermuxLeaksInDerivedPaths() {
        // Any derived path that still references "com.termux/files" indicates the rename did not propagate.
        String[] derived = {
            TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH,
            TermuxConstants.TERMUX_FILES_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
        };
        for (String p : derived) {
            Assert.assertFalse(
                "Derived path leaks upstream com.termux: " + p,
                p.startsWith("/data/data/com.termux/")
            );
        }
    }

    @Test
    public void buildConfigApplicationIdMatches() {
        // BuildConfig.APPLICATION_ID is generated from gradle's applicationId.
        Assert.assertEquals(EXPECTED_PACKAGE_NAME, com.termux.BuildConfig.APPLICATION_ID);
    }
}
