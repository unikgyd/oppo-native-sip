#!/system/bin/sh
# Leave user account data intact for rollback/reinstall. Remove module-owned exemptions.
dumpsys deviceidle whitelist -dev.mysip >/dev/null 2>&1
cmd appops set dev.mysip RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1
cmd appops set dev.mysip RUN_IN_BACKGROUND default >/dev/null 2>&1
cmd appops set dev.mysip WRITE_SMS default >/dev/null 2>&1
