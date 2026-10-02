# justTeams Fabric — PixelClub compatibility patch

Native Fabric 1.21.1 services over the exact `justTeams` 2.5.5 replica, retaining the existing Bukkit SQL/configuration/item interfaces and original attribution.

Baseline Fabric jar SHA-256: `162dfd8c551497d08fcfe64dbbcc09437812a8ca1ae3eddf442408e6cf72e86a`.
Donor Bukkit jar SHA-256: `36c725c1c15afcb546142b9933a40ee14499cffe55daa1d415f9cca7c4bef8af`.

Original: Kotori / [Roxest/justTeams](https://github.com/Roxest/justTeams), CC-BY-NC-SA-4.0 as declared by the exact artifact. PixelClub changes are identified by version `2.5.5+pxc.1`. Original classfiles/resources not replaced by this patch remain byte-identical.

Cutover preserves all donor tables and uses additive `pxc_teams_*` tables for transaction recovery and native chest payloads. Production activation requires cold-copy validation and rehearsed forward/reverse conversion; a successful build or boot alone does not authorize activation.

Recovery never guesses or blindly retries an external money outcome. Unresolved journal operations freeze affected bank accounts/teams and remain visible to operators.
