# Release notes

One file per release: `kuma3-12.md` for release 12, tagged `v12`. The workflow
(`.github/workflows/kuma3.yml`) refuses to publish a tagged release without it, and adds
`../release-template.md` and the exact source commits below it.

Write what changed since the previous release, short and plain, in English and Spanish.

To release: commit the notes file, then `git tag v12 && git push kuma3 v12`. Releases are tagged
`v<n>` because GitHub's release list sorts `kuma3-9` above `kuma3-10`; releases 1 to 11 were
first tagged `kuma3-<n>` (those tags stay, the releases were moved to `v<n>`). Backend and core
keep `kuma3-<n>` tags.
