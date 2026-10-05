# Release notes

One file per release, named after its tag: `kuma3-6.md` for the tag `kuma3-6`. The workflow
(`.github/workflows/kuma3.yml`) refuses to publish a tagged release without it, and adds
`../release-template.md` and the exact source commits below it.

Write what changed since the previous release, short and plain, in English and Spanish.

To release: commit the notes file, then `git tag kuma3-6 && git push kuma3 kuma3-6`.
