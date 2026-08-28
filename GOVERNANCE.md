# Governance

AgenticDroid currently uses a maintainer-led governance model. The goal is transparent technical
decision-making with a low barrier to useful contributions while the project is pre-release.

## Roles

**Contributors** are anyone who reports issues, improves documentation, reviews changes, tests
devices, or submits code. Contributions are recognized through Git history and release notes.

**Reviewers** are recurring contributors trusted to review particular areas. Reviewers may label,
triage, and recommend changes but do not merge their own pull requests without another review
when another qualified maintainer is available.

**Maintainers** manage releases, security reports, repository settings, roadmap priorities, and
merge decisions. The current maintainer and code owner is
[@JustinANelson](https://github.com/JustinANelson).

Roles are earned through sustained, constructive participation and can be expanded as the
community grows. Maintainers should document additions or removals in this file.

## Decisions

- Routine fixes and documentation changes are decided through pull-request review.
- Significant architecture, security model, dependency, data-handling, or compatibility changes
  should begin with an issue describing the problem, alternatives, risks, and migration impact.
- Maintainers seek rough consensus, but may make the final call when consensus is not possible.
- Decisions should be based on technical merit, user safety, maintainability, project scope, and
  the [roadmap](docs/ROADMAP.md), not contributor status.
- Security-sensitive decisions may be discussed privately until coordinated disclosure is safe.

## Review and merge policy

Pull requests need passing required checks and resolved review conversations. The author remains
responsible for correctness, licensing, and testing even when an AI tool helped create the change.
Maintainers may request smaller pull requests, additional tests, device evidence, or a design issue.

Maintainers normally squash-merge so each pull request becomes one coherent change. Direct pushes
to the default branch should be reserved for repository recovery or urgent administration.

## Conflicts of interest and conduct

Reviewers should disclose personal or commercial interests that could affect a decision and step
back when impartial review is not possible. Conduct is governed by
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Changing governance

Governance changes use the same public issue and pull-request process as other significant changes.
As additional maintainers join, the project should replace single-maintainer decisions with a
documented multi-maintainer voting and succession process.
