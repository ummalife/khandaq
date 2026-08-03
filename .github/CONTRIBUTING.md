# Contributing to Khandaq

## Signed commits are required on `master`

A repository ruleset requires **verified signatures** on every commit pushed to
`master` (security audit A17). Unsigned pushes are rejected with
`GH013: Commits must have verified signatures`.

Set up SSH commit signing once:

```sh
git config gpg.format ssh
git config user.signingkey ~/.ssh/id_ed25519.pub     # your key
git config commit.gpgsign true
git config tag.gpgsign true
```

Then add that **same SSH public key** to your GitHub account as a **Signing key**
(Settings → SSH and GPG keys → New SSH key → Key type: *Signing Key*), and make
sure your `git` author email is a verified email on that account. A test commit
should then show **Verified** on GitHub.

CI note: the changelog auto-update job creates its commit through the GitHub API
(`createCommitOnBranch`), so GitHub signs it and it satisfies the ruleset without
any bypass — see `.github/workflows/changelog.yml`.
