# Git Workflow — Consensus Bench Framework

Repo: `https://github.com/pdampanis/consensus-bench-framework.git`
Local: `/home/pdampani/Downloads/consensus-bench-thesis`

## Current: HTTPS (active)

Authenticated via GitHub CLI (`gh`) credential helper.
No SSH keys were created or modified for this repo.

```
# git config credential.https://github.com.helper
!/usr/bin/gh auth git-credential
```

The helper uses your existing `gh auth` session (logged in as `pdampanis`).
Your OpenBet `id_rsa` key and `~/.ssh/config` were left untouched.

```bash
cd /home/pdampani/Downloads/consensus-bench-thesis

# Stage & commit
git add .
git commit -m "your message"

# Push
git push origin main

# Pull
git pull origin main
```

## Future: SSH (planned)
See [SSH_SETUP.md](SSH_SETUP.md) for switch instructions.
