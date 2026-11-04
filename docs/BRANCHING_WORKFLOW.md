# Branching Workflow

This workflow keeps `main` stable while each development phase is implemented and tested independently. Phase branches are promoted only after their scope passes its validation gate.

## Branch Responsibilities

| Branch | Responsibility | Merge policy |
| --- | --- | --- |
| `main` | Stable, tested project baseline. Treat this as the source of truth for completed work. | No direct commits. Accept changes through a reviewed pull request after the relevant phase gate passes. |
| `develop` | Optional integration branch when work from multiple phases must be exercised together before promotion. It is not a required stop for a self-contained phase. | Merge phase branches here only for integration testing. Promote tested work to `main` with a pull request. |
| `phase1-core-ride` | Core ride behavior and the baseline ride-to-WLED path. | Pull request to `main` after Phase 1 exit criteria pass. |
| `phase2-bosch-validation` | Bosch and GPS speed-source validation, telemetry quality, and related fixes. | Pull request to `main` after Phase 2 exit criteria pass. |
| `phase3-cockpit-safety` | Cockpit workflows and rider-facing safety behavior. | Pull request to `main` after Phase 3 exit criteria pass. |
| `phase4-audio-reactive` | Audio capture and audio-reactive lighting behavior. | Pull request to `main` after Phase 4 exit criteria pass. |
| `phase5-patterns-tuning` | Lighting patterns, tuning workflows, and supporting tools. | Pull request to `main` after Phase 5 exit criteria pass. |

## Starting and Promoting a Phase

1. Start a phase from the latest `main` so its changes are based on the current stable baseline. Keep changes within that phase's scope; split unrelated work into another branch.
2. Push the phase branch and open a pull request to `main`. Do not push directly to `main`.
3. Run the tests and build checks relevant to the changed code. For hardware-dependent behavior, record the device, firmware, setup, and observed results; unit tests alone do not prove hardware behavior.
4. Review the diff, resolve review feedback, and confirm the pull request's checks pass. Do not merge while a required check is failing or a known safety issue is unresolved.
5. Merge only after the phase exit criteria in the relevant specification or test plan are met. Record test evidence in the pull request.
6. After merging, update `develop` from `main` if it is being used for integration. Start the next phase from the updated `main`.

If multiple phases must work together before any is ready for `main`, merge them into `develop` for integration testing. Keep the final promotion explicit: open a pull request from the tested integration state to `main`, with the combined scope and test evidence documented.

## Pull Request Checklist

- [ ] The change belongs to the branch's phase and does not include unrelated work.
- [ ] The relevant unit tests and build checks pass.
- [ ] Hardware-dependent checks include repeatable setup and observed results, or are clearly marked as not yet verified.
- [ ] The diff contains no accidental generated files, secrets, or local-only configuration.
- [ ] The pull request describes behavior changed, tests run, and remaining limitations.
- [ ] The phase's documented exit criteria are satisfied before merging to `main`.

## Branch Maintenance

- Keep phase branches focused; avoid using one phase branch as a general-purpose feature branch.
- If a phase is still in progress after `main` changes, bring the latest `main` into that phase branch and rerun its relevant checks.
- After a phase is merged and no follow-up work remains on its branch, delete the local and remote branch. Recreate it from the latest `main` when that phase needs another work cycle.
- Keep `develop` aligned with `main` when it is idle. Do not treat it as a substitute for validating a phase branch.