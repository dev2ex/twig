# Twig Contributor License Agreement

## Why this exists (read this first)

Twig is licensed under **GPL-3.0-only**. That license was not entirely a free
choice: two of its dependencies — the Termux terminal emulator and the Jellyfin
FFmpeg decoder — are GPLv3, and linking them makes the whole application GPLv3.

Keeping the ability to license the project's **own** code differently matters for
concrete reasons:

- **The copyleft is inherited, not chosen.** If those dependencies are ever
  replaced — the terminal with an Apache-2.0 implementation, the decoder with a
  self-built LGPL FFmpeg build — the project's own code could move to a more
  permissive license. That option only exists if the whole codebase can be
  relicensed.
- **Some distribution channels impose terms that conflict with the GPL.** Where
  that happens, the usual remedy is to grant separate terms for that particular
  build, which requires holding all copyright in the code being shipped.
- **License problems need room to be fixed.** Adding an exception, moving to a
  weaker copyleft license (as VideoLAN did for VLC), or dual-licensing all
  require the same thing: one party able to speak for the whole codebase.

None of this is possible if parts of the codebase are owned by contributors who
only ever granted GPL rights. Contacting every past contributor after the fact is
usually not feasible, so the grant has to be in place from the start.

**What this CLA does not do:** it does not take your copyright away, it does not
make Twig any less free, and it does not let anyone un-free a release that has
already shipped. Every version published under GPL-3.0 stays under GPL-3.0
forever — that is irrevocable, and nothing here changes it.

---

## Agreement

By submitting a Contribution to the Twig project ("the Project"), You agree to
the following terms.

### 1. Definitions

- **"You"** means the individual or legal entity submitting a Contribution.
- **"Contribution"** means any work of authorship You intentionally submit to the
  Project — including code, documentation, tests, configuration and translations —
  through a pull request, patch, issue attachment, or any other means.
- **"Owner"** means vale, the original author and maintainer of the Project, and
  any successor to whom the Project's rights are lawfully transferred.

### 2. Copyright

**You retain full ownership of the copyright in Your Contribution.** This
agreement is a license, not an assignment. You remain free to use, publish and
license Your Contribution elsewhere on any terms You choose.

### 3. Copyright license grant

You grant to the Owner a perpetual, worldwide, non-exclusive, royalty-free,
irrevocable license to reproduce, prepare derivative works of, publicly display,
publicly perform, sublicense and distribute Your Contribution and such derivative
works, **under any license terms the Owner selects, including copyleft,
permissive and proprietary terms**.

You also grant the same rights to every recipient of the Project under the
license the Project is distributed with (currently GPL-3.0-only).

### 4. Patent license grant

You grant to the Owner and to recipients of the Project a perpetual, worldwide,
non-exclusive, royalty-free, irrevocable (except as stated below) patent license
to make, have made, use, offer to sell, sell, import and otherwise transfer Your
Contribution. This license applies only to those patent claims licensable by You
that are necessarily infringed by Your Contribution alone, or by the combination
of Your Contribution with the Project.

If any entity institutes patent litigation alleging that Your Contribution, or
the Project to which You contributed, constitutes direct or contributory patent
infringement, then any patent licenses granted to that entity under this
agreement terminate as of the date such litigation is filed.

### 5. Your representations

You represent that:

1. Each Contribution is Your original creation, or You have the right to submit
   it under the terms of this agreement;
2. If Your Contribution includes work that is not Your original creation, You
   have identified its source and its license, and any restrictions that apply;
3. If Your employer has rights to intellectual property You create, You have
   received permission to make the Contribution on behalf of that employer, or
   Your employer has waived such rights;
4. You are legally entitled to grant the licenses above.

### 6. No warranty, no obligation

Your Contribution is provided "AS IS", without warranties or conditions of any
kind, express or implied. You are not expected to provide support for Your
Contribution.

The Owner is under no obligation to accept, merge or use any Contribution.

### 7. Notice of changed circumstances

You agree to notify the Owner if You become aware of any facts that would make
the representations in section 5 inaccurate.

---

## How to sign

No paperwork, no external service. Either of the following counts as agreement:

**Option A — one line in your pull request description:**

```
I have read the CLA (CLA.md) and I agree to its terms.
```

**Option B — sign off your commits:**

```
git commit -s
```

which appends `Signed-off-by: Your Name <your@email>` to the commit message. By
signing off you state that you agree to both the
[Developer Certificate of Origin](https://developercertificate.org/) and this CLA.

For trivial contributions — a typo fix, a one-line correction — the maintainer
may waive this requirement at their discretion.
