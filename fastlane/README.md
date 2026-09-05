# Store metadata (F-Droid / Triple-T layout)

F-Droid reads the app listing straight from this directory. Nothing here affects
the build — it is text and images only.

```
metadata/android/<locale>/
  title.txt              app name              max 50 chars
  short_description.txt  one-line summary      max 80 chars
  full_description.txt   the listing body      max 4000 chars
  images/
    icon.png             512x512
    featureGraphic.png   1024x500, banner above the description
    phoneScreenshots/    1.png, 2.png, …  (png / jpg / jpeg only)
  changelogs/<versionCode>.txt                 max 500 chars
```

Locales present: `en-US`, `zh-CN`. Keep both in sync when either changes.

## Rules that are easy to get wrong

- **Do not hard-wrap paragraphs.** F-Droid turns every newline into `<br>`, so a
  paragraph wrapped at 80 columns renders as a column of short broken lines on a
  phone. Write each paragraph as one long line; separate paragraphs with a blank
  line.
- **Only this HTML subset is rendered**: `b, big, blockquote, br, cite, em, i,
  li, ol, small, strike, strong, sub, sup, tt, u, ul` (plus `a href`).
  `img`, `video`, `iframe` and `script` are stripped. Markdown is *not* rendered
  — use `<ul><li>` for lists, not `-`.
- **Every release needs its own changelog file**, named after the **versionCode**
  (not the version name): bumping to 282 means adding
  `changelogs/282.txt` in both locales. A missing file is not an error, it just
  means that version shows no changelog.
- **The F-Droid build is the `libre` flavor — it has no RAR support.** The
  descriptions say so. If the flavors ever diverge further, update that paragraph.

## Still missing: screenshots

`images/phoneScreenshots/` is empty; the listing works without it but looks
sparse. Capture on a real device (`adb exec-out screencap -p > 1.png`), then keep
the same order in both locales. Four that make the case:

1. Both panes side by side, a folder on one side being copied to a remote source
2. Tree-style in-place expansion, with an archive opened like a directory
3. The disk-usage treemap
4. Wi-Fi sharing mounted as a network drive from a desktop file manager

The icon and feature graphic are generated from `app/src/main/res/drawable/ic_launcher.xml`
and can be regenerated with `rsvg-convert`; see the commit that added them.
