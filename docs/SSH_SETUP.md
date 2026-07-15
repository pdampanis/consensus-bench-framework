# SSH Setup for GitHub (Planned)

> Currently using HTTPS with `gh` credential helper. Switch to SSH if/when needed.

## Generate Key

```bash
ssh-keygen -t ed25519 -f ~/.ssh/github_rsa -C "pdampanis"
# Leave passphrase empty (press Enter twice)
```

## Upload Public Key

```bash
cat ~/.ssh/github_rsa.pub
```

Add to: https://github.com/settings/ssh/new

## SSH Config

Append to `~/.ssh/config`:

```
Host github.com
  HostName github.com
  IdentityFile ~/.ssh/github_rsa
  IdentitiesOnly yes
```

## Switch Remote

```bash
git remote set-url origin git@github.com:pdampanis/consensus-bench-framework.git
```

## Verify

```bash
ssh -T git@github.com
# Expected: "Hi pdampanis! You've successfully authenticated..."
```

## Rollback

```bash
# Remove the Host github.com block from ~/.ssh/config
rm ~/.ssh/github_rsa ~/.ssh/github_rsa.pub
git remote set-url origin https://github.com/pdampanis/consensus-bench-framework.git
```
