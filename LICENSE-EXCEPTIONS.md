# Additional Permissions and Notices

Twig as a whole is licensed under **GPL-3.0-only**; see [LICENSE](LICENSE).

This file supplements that license with two things: an **additional permission**
under section 7 of the GPL, and the third-party notices that must accompany the
program. The complete dependency inventory lives in
[THIRD_PARTY.md](THIRD_PARTY.md).

---

## 1. Additional permission for linking with UnRAR-licensed code (GPL §7)

> **Additional permission under GNU GPL version 3 section 7**
>
> Copyright (C) 2026 vale
>
> As a special exception, the copyright holder of Twig gives permission to link
> the code of this program with software released under the UnRAR license — in
> particular the `junrar` library (https://github.com/junrar/junrar), which
> provides read-only RAR archive support — and to distribute the resulting
> combined work. You may extend this exception to your version of the program,
> but you are not obliged to do so. If you do not wish to do so, delete this
> exception statement from your version.
>
> This additional permission applies **only** to the UnRAR-licensed components
> listed in [THIRD_PARTY.md](THIRD_PARTY.md). It grants no permission with
> respect to any other GPL-incompatible code.

**Why this is needed.** The UnRAR license carries a use restriction — the code
may not be used to re-create the RAR compression algorithm. Restrictions of that
kind are exactly what GPL section 7 forbids adding to a GPL work, so distributing
"Twig + junrar" as a combined work is, strictly read, in conflict. As the sole
copyright holder of Twig's own code, the author grants the exception above so
that the combined work can be distributed lawfully.

If you fork Twig and remove RAR support, you can drop this section along with it.

---

## 2. Notice required by the UnRAR license

As required by clause 2 of the UnRAR license:

> **The RAR-handling code in this program may not be used to develop a RAR
> (WinRAR) compatible archiver.**

Twig provides read-only RAR extraction. It does not implement, and does not
attempt to implement, RAR compression.

---

## 3. LGPL components and relinking

Twig ships the following LGPL-licensed components:

- **libsmb2** 2.3.0 (`fs-smb/src/main/cpp/libsmb2/`, LGPL-2.1-or-later)
- **FFmpeg**, via `org.jellyfin.media3:media3-ffmpeg-decoder` (FFmpeg upstream is
  LGPL-2.1-or-later; that AAR itself is released under GPL-3.0)

Section 6 of the LGPL-2.1 requires that recipients be able to relink the program
against a modified version of the library. Because Twig is distributed in full
under GPL-3.0 with complete source and build scripts — and LGPL-2.1 section 3
permits conversion to GPL v2 or later, which is compatible with GPL-3.0 — this
requirement is satisfied by shipping the complete corresponding source and a
reproducible build.

The libsmb2 source in `fs-smb/src/main/cpp/libsmb2/` is an unmodified upstream
copy; its license texts (`COPYING`, `LICENCE-LGPL-2.1.txt`) are provided
alongside it.

---

## 4. Trademarks

**The "Twig" name and the application icon are not covered by the GPL grant.**
All rights in them are reserved by the original author. GPL-3.0 section 7(e)
explicitly permits declining to grant rights under trademark law.

In practice this means:

- You are free to fork, modify and redistribute the **code**;
- but please **change the name and the icon** when you distribute a modified
  version, so that users are not misled about its origin.

This restriction exists only to prevent confusion. It places no limit on any
freedom the GPL grants over the code itself.

---

## 5. Relicensing of the project's own code

Copyright in Twig's own code is held by the original author, who reserves the
right to license that code under different terms as well — for example, to
relicense it more permissively if the GPL-licensed dependencies that force the
current license (see [THIRD_PARTY.md](THIRD_PARTY.md), section 2.1) are ever
replaced.

This does not affect the irrevocability of the grant already made:
**any version released under GPL-3.0 stays under GPL-3.0, permanently.**

This reservation only holds as long as every contribution carries the rights
described in [CLA.md](CLA.md).
