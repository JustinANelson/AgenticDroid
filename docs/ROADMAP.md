# Roadmap

This roadmap communicates direction rather than promising dates. Issues and pull requests remain
the source of truth for active work, and safety or platform changes may reorder priorities.

## Now: trustworthy contributor foundation

- Keep the ordinary contributor gate fast, deterministic, and green.
- Resolve native-runtime attribution and corresponding-source automation.
- Record reproducible provenance for tracked native overrides.
- Expand tests around archive containment, shell escaping, credential lifecycle, and LAN/SSH paths.
- Improve accessibility and document tested device/Android combinations.

## Next: usable pre-release builds

- Establish repeatable signed release engineering and an SBOM.
- Complete real-device validation for bootstrap, upgrade, wipe, and 16 KB page-size behavior.
- Improve editor ergonomics, language-server coverage, and failure recovery.
- Harden long-running/background agent sessions under Android process limits.
- Define storage-format migration and compatibility policy before the first supported release.

## Later: sustainable ecosystem

- Evaluate a distribution model compatible with Android platform and store policies.
- Broaden architecture/device support only when the complete native closure is reproducible.
- Add extension points without weakening workspace, credential, and process isolation.
- Grow reviewer and maintainer coverage to reduce the single-maintainer dependency.

## Proposing roadmap work

Open a feature issue describing the user problem, evidence, alternatives, security/privacy impact,
dependencies, and a small first milestone. A roadmap item is not assigned until a maintainer and
contributor explicitly agree on scope.
