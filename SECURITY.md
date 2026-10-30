# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue.

- **On GitHub:** open this repository's **Security** tab and choose **Report a vulnerability**.
- **By email:** support@coffeehauslabs.com

Include what you found, the version of Kova and the phone you used, and the steps to reproduce it.
A proof of concept helps but is not required.

## What happens next

- We reply within 3 working days to confirm we have your report.
- We keep you updated while we investigate and fix it.
- We publish a fix and a GitHub security advisory, crediting you by name unless you ask us not to.

Please give us a reasonable time to ship a fix before you disclose the issue publicly. We will not
take legal action against good-faith research that stays within this policy and only touches your
own devices and data.

Kova does not pay bug bounties.

## Supported versions

Fixes go into the latest release on Google Play and the current source in this repository. Older
releases are not patched.

## How Kova's security works

[docs/security-design.md](docs/security-design.md) covers the cryptography, the keys and what Kova
does and does not protect against. [FORMAT.md](FORMAT.md) has the full on-disk format.
