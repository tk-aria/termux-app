package com.termux.app;

import com.termux.shared.granizm.GranizmProotWrapper;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/**
 * Verify that the proot wrap layer produces a correct exec command for the
 * granizm fork's use case (binding our app data dir as /data/data/com.termux).
 *
 * Note: shouldWrap() depends on the actual proot binary being present on disk,
 * so we cannot test that directly here. We test the wrap() output shape and
 * env vars in isolation.
 */
public class GranizmProotWrapperTest {

    private static final String PREFIX = "/data/data/net.granizm.termux_app/files/usr";

    @Test
    public void wrappedExecutableIsProot() {
        GranizmProotWrapper.WrapResult result = GranizmProotWrapper.wrap(
            PREFIX + "/bin/login", new String[] { "-login" });
        Assert.assertEquals(PREFIX + "/bin/proot", result.executable);
    }

    @Test
    public void wrappedArgvZeroIsProot() {
        // argv[0] MUST be "proot" (not "-login"). proot reads argv[0] for
        // self-identification and loader path discovery; "-login" breaks it.
        // Login-shell semantics are preserved by `bin/login` itself which
        // explicitly invokes `bash -l`.
        GranizmProotWrapper.WrapResult result = GranizmProotWrapper.wrap(
            PREFIX + "/bin/login", new String[] { "-login" });
        Assert.assertEquals("proot", result.arguments[0]);
    }

    @Test
    public void wrappedArgsContainKillOnExit() {
        GranizmProotWrapper.WrapResult result = GranizmProotWrapper.wrap(
            PREFIX + "/bin/login", new String[] { "-login" });
        Assert.assertTrue(
            "expected --kill-on-exit in wrapped args, got: " + Arrays.toString(result.arguments),
            Arrays.asList(result.arguments).contains("--kill-on-exit"));
    }

    @Test
    public void wrappedArgsContainCriticalComTermuxBind() {
        // The whole point: /data/data/net.granizm.termux_app/files must be
        // bound to /data/data/com.termux/files for upstream APT to work.
        GranizmProotWrapper.WrapResult result = GranizmProotWrapper.wrap(
            PREFIX + "/bin/login", new String[] { "-login" });
        boolean found = false;
        for (int i = 0; i < result.arguments.length - 1; i++) {
            if ("-b".equals(result.arguments[i])
                && result.arguments[i + 1].equals(
                    TermuxConstants.TERMUX_FILES_DIR_PATH + ":/data/data/com.termux/files")) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(
            "expected -b " + TermuxConstants.TERMUX_FILES_DIR_PATH + ":/data/data/com.termux/files, args: "
                + Arrays.toString(result.arguments),
            found);
    }

    @Test
    public void wrappedArgsEndWithOriginalCommandPlusUserArgs() {
        // After all proot args, the original executable + its args (minus argv[0])
        // must appear in order so the shell runs as the user expects.
        GranizmProotWrapper.WrapResult result = GranizmProotWrapper.wrap(
            PREFIX + "/bin/login", new String[] { "-login", "--noprofile" });

        String[] tail = Arrays.copyOfRange(
            result.arguments, result.arguments.length - 2, result.arguments.length);
        Assert.assertArrayEquals(
            new String[] { PREFIX + "/bin/login", "--noprofile" }, tail);
    }

    @Test
    public void prootEnvironmentHasLoaderPaths() {
        Map<String, String> env = GranizmProotWrapper.getProotEnvironment();
        Assert.assertEquals(
            PREFIX + "/libexec/proot/loader", env.get("PROOT_LOADER"));
        Assert.assertEquals(
            PREFIX + "/libexec/proot/loader32", env.get("PROOT_LOADER_32"));
        Assert.assertEquals(
            PREFIX + "/tmp", env.get("PROOT_TMP_DIR"));
    }

    @Test
    public void prootEnvironmentSetsSentinel() {
        // The sentinel lets nested invocations short-circuit (defense in depth
        // for scripts that may re-launch login under proot).
        Map<String, String> env = GranizmProotWrapper.getProotEnvironment();
        Assert.assertEquals("1", env.get(GranizmProotWrapper.SENTINEL_ENV_VAR));
    }

    @Test
    public void shouldNotWrapNonShellPaths() {
        // Arbitrary native command executions (not under $PREFIX/bin) must not
        // be wrapped — wrap is for interactive shells only.
        Assert.assertFalse(
            GranizmProotWrapper.shouldWrap("/system/bin/sh"));
        Assert.assertFalse(
            GranizmProotWrapper.shouldWrap("/data/data/net.granizm.termux_app/files/usr/libexec/foo"));
        Assert.assertFalse(
            GranizmProotWrapper.shouldWrap(null));
    }
}
