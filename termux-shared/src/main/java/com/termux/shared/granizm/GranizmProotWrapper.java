package com.termux.shared.granizm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Granizm fork helper: wrap shell exec with `proot` so that upstream termux
 * APT packages (built with {@code /data/data/com.termux/files/usr/} paths
 * baked in) work transparently after install.
 *
 * <p>proot uses ptrace to intercept syscalls and re-route paths. We bind
 * our actual files dir at the (non-existent) {@code /data/data/com.termux/}
 * location so apt-installed binaries can find their libraries and configs.
 *
 * <p>This wrapper is the proper layer for that path translation: it stays
 * in Java code (easy to maintain, easy to disable), keeps the bootstrap
 * scripts untouched, and survives upstream merges of {@code termux-app}.
 *
 * <p>Trade-off: proot's ptrace adds ~5-20% per-syscall overhead. Acceptable
 * for shell sessions; not used for non-shell command executions.
 */
public final class GranizmProotWrapper {

    /** Path to the bundled proot binary. */
    public static final String PROOT_PATH =
        TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";

    /** Path to the proot loader (64-bit). */
    public static final String PROOT_LOADER =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/libexec/proot/loader";

    /** Path to the proot loader (32-bit). */
    public static final String PROOT_LOADER_32 =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/libexec/proot/loader32";

    /** Where proot stores temporary files (must be on a non-noexec mount). */
    public static final String PROOT_TMP_DIR =
        TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH;

    /** Upstream "com.termux" prefix that gets bound to our actual path. */
    public static final String UPSTREAM_COM_TERMUX_FILES = "/data/data/com.termux/files";

    /** Upstream "com.termux" cache that gets bound to our actual path. */
    public static final String UPSTREAM_COM_TERMUX_CACHE = "/data/data/com.termux/cache";

    /** Sentinel env var used to detect (and skip) double-wrapping. */
    public static final String SENTINEL_ENV_VAR = "TERMUX_GRANIZM_PROOT_ACTIVE";

    private GranizmProotWrapper() {}

    /**
     * @return {@code true} if the given executable should be wrapped with proot.
     *
     * <p>r10 change: proot wrap is now <strong>opt-in</strong> via the env var
     * {@code TERMUX_GRANIZM_USE_PROOT=1}. The rebuilt bootstrap has the granizm
     * paths baked into every package, so the interactive shell, basic utilities,
     * {@code apt update}, {@code apt-get download}, {@code ssh}, etc. work
     * <strong>without proot</strong>. The only thing proot was needed for was
     * upstream {@code dpkg -i} where deb files contain hardcoded
     * {@code /data/data/com.termux/...} paths.
     *
     * <p>On a number of arm64 Android devices, the bundled proot binary fails
     * with ENOSYS during its setup (likely kernel/seccomp incompatibility),
     * which previously broke the very first shell launch. Making the wrap
     * opt-in guarantees the shell starts everywhere; users who need
     * {@code dpkg -i} of upstream packages can either:
     *   <ol>
     *     <li>{@code export TERMUX_GRANIZM_USE_PROOT=1} in
     *         {@code ~/.bashrc} (if proot works on their device), or</li>
     *     <li>use the {@code granizm-pkg} wrapper (see scripts) which handles
     *         path-translated install without proot.</li>
     *   </ol>
     */
    public static boolean shouldWrap(@Nullable String executable) {
        if (executable == null) return false;
        if (!executable.startsWith(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/")) return false;
        // Opt-in: only wrap when explicitly enabled via env var.
        String optIn = System.getenv("TERMUX_GRANIZM_USE_PROOT");
        if (optIn == null || !optIn.equals("1")) return false;
        File proot = new File(PROOT_PATH);
        return proot.exists() && proot.canExecute();
    }

    /**
     * Wrap a command (executable + arguments) so that it runs inside a proot
     * environment with our path bind translation active.
     *
     * <p>Returns an array {@code {newExecutable, newArguments}} where:
     * <ul>
     *   <li>{@code newExecutable} is the proot binary path</li>
     *   <li>{@code newArguments[0]} is the same logical "process name" as before
     *       (the basename of the original executable, possibly with a leading
     *       "-" for login shells) so {@code ps} output looks unchanged</li>
     *   <li>{@code newArguments[1..N]} contains the proot args followed by the
     *       original executable and original args[1..]</li>
     * </ul>
     */
    @NonNull
    public static WrapResult wrap(@NonNull String originalExecutable,
                                  @NonNull String[] originalArguments) {
        // Make sure PROOT_TMP_DIR exists before proot tries to use it. Without
        // this, proot's cleanup-on-failure path emits "trying to remove a
        // directory outside of '<PROOT_TMP_DIR>'" garbage that obscures the
        // real failure (and on some kernels even prevents proot from setting up).
        new File(PROOT_TMP_DIR).mkdirs();

        List<String> prootArgs = new ArrayList<>();
        prootArgs.add("--kill-on-exit");
        // Start the guest in the user's HOME (a known-existing path inside the
        // proot view) so proot doesn't inherit a host-side cwd that may not be
        // visible after bindings are applied.
        prootArgs.add("--cwd=" + TermuxConstants.TERMUX_HOME_DIR_PATH);

        // System binds: required for Termux binaries to find /system/bin/linker64,
        // /apex libs (Android 10+), /linkerconfig (Android 11+), /vendor libs, etc.
        addBindIfExists(prootArgs, "/system");
        addBindIfExists(prootArgs, "/vendor");
        addBindIfExists(prootArgs, "/apex");
        addBindIfExists(prootArgs, "/linkerconfig");
        addBindIfExists(prootArgs, "/proc");
        addBindIfExists(prootArgs, "/dev");
        addBindIfExists(prootArgs, "/sys");
        // /data: covers our own app data; also necessary for /data/misc etc.
        addBindIfExists(prootArgs, "/data");
        addBindIfExists(prootArgs, "/storage");

        // The critical bind: make our files dir visible as com.termux's files dir.
        // Order matters — these come after /data so they win for overlapping paths.
        prootArgs.add("-b");
        prootArgs.add(TermuxConstants.TERMUX_FILES_DIR_PATH + ":" + UPSTREAM_COM_TERMUX_FILES);
        prootArgs.add("-b");
        prootArgs.add(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "/cache:" + UPSTREAM_COM_TERMUX_CACHE);

        // Build the final argv. argv[0] MUST be "proot" (the wrapper's own name)
        // because proot reads argv[0] for self-identification, error messages
        // (`fatal error: see \`<argv[0]> --help\``), and possibly loader path
        // discovery via realpath(argv[0]). Setting argv[0] to "-login" (which
        // would normally signal a login shell) confuses proot completely and
        // leads to bogus ENOSYS errors during loader setup.
        //
        // Login-shell semantics are still preserved: `bin/login` (the wrapped
        // executable) explicitly invokes `exec "$SHELL" -l` internally, so the
        // `-l` flag is what tells bash to run as a login shell — not argv[0]
        // having a leading "-".
        List<String> finalArgs = new ArrayList<>();
        finalArgs.add("proot");
        finalArgs.addAll(prootArgs);
        finalArgs.add(originalExecutable);
        for (int i = 1; i < originalArguments.length; i++) {
            finalArgs.add(originalArguments[i]);
        }

        return new WrapResult(PROOT_PATH, finalArgs.toArray(new String[0]));
    }

    /**
     * Get the proot-related environment variables that must be set so that
     * proot can find its loader and tmp dir at runtime. These augment the
     * existing shell environment.
     */
    @NonNull
    public static Map<String, String> getProotEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PROOT_LOADER", PROOT_LOADER);
        env.put("PROOT_LOADER_32", PROOT_LOADER_32);
        env.put("PROOT_TMP_DIR", PROOT_TMP_DIR);
        // Disable proot's seccomp acceleration — on some arm64 Android kernels
        // (notably 4.14-4.19 with strict seccomp filter) the seccomp path returns
        // ENOSYS on execve, producing
        //   "proot error: execve(...): Function not implemented".
        // Disabling falls back to pure ptrace (slower but universally compatible).
        // r9 fix for issues seen on user's arm64 device.
        env.put("PROOT_NO_SECCOMP", "1");
        env.put(SENTINEL_ENV_VAR, "1");
        return env;
    }

    /**
     * Merge proot env vars into an existing environment map.
     */
    public static void applyProotEnvironment(@NonNull HashMap<String, String> environment) {
        environment.putAll(getProotEnvironment());
    }

    /** Container for the wrapped exec result. */
    public static final class WrapResult {
        public final String executable;
        public final String[] arguments;

        public WrapResult(@NonNull String executable, @NonNull String[] arguments) {
            this.executable = executable;
            this.arguments = arguments;
        }
    }

    private static void addBindIfExists(@NonNull List<String> args, @NonNull String path) {
        if (new File(path).exists()) {
            args.add("-b");
            args.add(path + ":" + path);
        }
    }

    @NonNull
    private static String basename(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
